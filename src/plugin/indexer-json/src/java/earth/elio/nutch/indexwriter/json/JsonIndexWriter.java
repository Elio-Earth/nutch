/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package earth.elio.nutch.indexwriter.json;

import java.io.DataOutputStream;
import java.lang.invoke.MethodHandles;
import java.io.IOException;
import java.net.Inet4Address;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.UUID;

import net.minidev.json.reader.JsonWriterI;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.compress.GzipCodec;
import org.apache.hadoop.util.ToolRunner;
import org.apache.nutch.indexer.*;
import org.apache.nutch.util.NutchConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minidev.json.JSONObject;
import net.minidev.json.JSONStyle;
import net.minidev.json.JSONValue;

/**
 * JsonIndexWriter. This pluggable indexer writes the fields configured to a file in json-lines
 * format (one json object per line) separated by newlines. It takes the configured base output path
 * and writes the files to a unique location so that it can be run in parallel.
 */
public class JsonIndexWriter implements IndexWriter {

    private static final Logger LOG = LoggerFactory
            .getLogger(MethodHandles.lookup().lookupClass());
    private static final String ENCODING = "UTF-8";
    private static final DateFormat DATE_PARTITION_FORMAT = new SimpleDateFormat("'y'=yyyy/'m'=MM/'d'=dd/'h'=HH");
    private static final DateFormat DATE_VALUE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss+00:00");

    private Configuration config;
    /**
     * A set of fields to force to be single-valued. This helps fields maintain a consistent schema
     * across documents. Originally created to handle HTML meta attributes which sometimes are duplicated on a page.
     */
    private final Set<String> singleFields = new HashSet<>();
    /**
     * A set of fields to force to be multivalued. This helps fields maintain a consistent schema
     * across documents.
     */
    private final Set<String> arrayFields = new HashSet<>();

    /** output path / directory */
    private String baseOutputPath = "";

    /** If this value is non-null, then it should either contain the compression algorithm to use
     * or "false". "false" means it will not compress. If the value is null or an empty string then
     * it will not compress the file.
     */
    private String compress;

    /** Controls whether to write the record to the "large" path or the
     * "normal" path. If the binary content field is larger than this number
     * for a record it is written to the "large" path. This gives you the
     * control to split off large records from the other parts of the dataset
     * so that downstream systems do not choke on these large records.*/
    private int binaryContentLargeCutoffChars;
    /**
     * The path to write "large" files if their binary content is larger
     * than binaryContentLengthCutoff. We write a single file per record.
     */
    private String largeBaseOutputPath = "";


    private final static Set<String> ACCEPTED_COMPRESSIONS = new HashSet<>(1);
    static {
        ACCEPTED_COMPRESSIONS.add(JsonConstants.GZIP);

        // set the date format for date objects to be ISO 8601 by default
        JSONValue.registerWriter(
            Date.class,
            new JsonWriterI<Date>() {
                public void writeJSONString(Date value, Appendable out, JSONStyle compression) throws IOException {
                    out.append('"');
                    JSONValue.escape(DATE_VALUE_FORMAT.format(value), out, compression);
                    out.append('"');
                }
            }
        );
    }
    protected OpenJsonStream openJsonStream;

    @Override
    public void open(Configuration conf, String name) throws IOException {
        //Implementation not required
    }

    /**
     * Initializes the internal variables from a given index writer configuration.
     *
     * @param parameters Params from the index writer configuration.
     * @throws IOException Some exception thrown by writer.
     */
    @Override
    public void open(IndexWriterParams parameters) throws IOException {
        singleFields.addAll(retrieveFields(parameters, JsonConstants.SINGLE_FIELDS));
        if(parameters.get(JsonConstants.ARRAY_FIELDS, "").trim().length() > 0) {
            arrayFields.addAll(retrieveFields(parameters, JsonConstants.ARRAY_FIELDS));
        }

        LOG.info("fields =");
        for (String f : singleFields) {
            LOG.info("\t" + f);
        }
        for (String f : arrayFields) {
            LOG.info("\t" + f + " (array)");
        }

        baseOutputPath = parameters.get(JsonConstants.JSON_BASE_OUTPUT_PATH);
        if(StringUtils.isBlank(baseOutputPath)) {
            throw new IOException("Base output path is missing");
        } else {
            LOG.info("Base Output path is {}", baseOutputPath);
        }
        // standardize the base output path to NOT have a trailing slash
        if (baseOutputPath.endsWith("/")) {
            baseOutputPath = baseOutputPath.substring(0, baseOutputPath.length() - 1);
        }
        compress = parameters.get(JsonConstants.COMPRESS, "").trim().toLowerCase();
        if (shouldCompressFile() && !ACCEPTED_COMPRESSIONS.contains(compress)) {
            throw new IOException("Unsupported compression type " + compress);
        }

        // configuration for large files
        binaryContentLargeCutoffChars = parameters.getInt(JsonConstants.BINARY_CONTENT_LENGTH_LARGE_CUTOFF, -1);
        largeBaseOutputPath = parameters.get(JsonConstants.LARGE_JSON_BASE_OUTPUT_PATH);
        if(StringUtils.isBlank(largeBaseOutputPath)) {
            if (binaryContentLargeCutoffChars > 0) {
                throw new IOException(
                    "Large record cutoff defined at "
                        + binaryContentLargeCutoffChars +
                        " characters but "
                        + JsonConstants.LARGE_JSON_BASE_OUTPUT_PATH
                        + " is not defined."
                );
            } else {
                LOG.info("No routing for large files configured.");
            }
        } else {
            // standardize the base output path to NOT have a trailing slash
            if (largeBaseOutputPath.endsWith("/")) {
                largeBaseOutputPath = largeBaseOutputPath.substring(0, largeBaseOutputPath.length() - 1);
            }
            if (binaryContentLargeCutoffChars > 0) {
                LOG.info("Large Base Output path is {} and files with binary content > {} characters will be routed there", largeBaseOutputPath,
                    binaryContentLargeCutoffChars);
            } else {
                LOG.info("No routing to Large Base Output path as {} is not defined", JsonConstants.LARGE_JSON_BASE_OUTPUT_PATH);
            }
        }

        openJsonStream = createStream(buildOutputPath());
    }

    private OpenJsonStream createStream(Path outputDir) throws IOException {
        FileSystem fs = outputDir.getFileSystem(config);
        // we do not want to write checksum files ever since it blows up EMR/Athena
        fs.setWriteChecksum(false);
        if (!fs.exists(outputDir)) {
            fs.mkdirs(outputDir);
        }

        String hostName = Inet4Address.getLocalHost().getHostAddress();
        // default to local if we can't find the hostname
        if (StringUtils.isBlank(hostName)) {
            hostName = "local";
        }

        String filename = String.format("%s-%s-%s.jsonl", hostName, Long.valueOf(System.currentTimeMillis()).toString(), UUID.randomUUID());
        if (shouldCompressFile()) {
            GzipCodec codec = new GzipCodec();
            codec.setConf(config);
            filename += codec.getDefaultExtension();

            Path outFile = new Path(outputDir, filename);
            if (fs.exists(outFile)) {
                // clean-up
                LOG.warn("Removing existing output path {}", outFile);
                fs.delete(outFile, true);
            }

            DataOutputStream jsonOut = new DataOutputStream(codec.createOutputStream(fs.create(outFile)));
            LOG.info("Opening file (to output): {}", outFile);
            return new OpenJsonStream(jsonOut, outFile);
        } else {
            Path outFile = new Path(outputDir, filename);
            if (fs.exists(outFile)) {
                // clean-up
                LOG.warn("Removing existing output path {}", outFile);
                fs.delete(outFile, true);
            }
            DataOutputStream jsonOut = new DataOutputStream(fs.create(outFile));
            LOG.info("Opening file (to output): {}", outFile);
            return new OpenJsonStream(jsonOut, outFile);
        }
    }

    private Collection<? extends String> retrieveFields(IndexWriterParams parameters, String configFieldName) throws IOException {
        String[] values = parameters.getStrings(configFieldName, null);
        if (values == null) {
            throw new IOException("Field " + configFieldName + " is missing value for JsonIndexWriter in index-writers.xml.");
        } else if (values.length == 1 && values[0].trim().length() == 0) {
            throw new IOException("Field " + configFieldName + " has a single empty value for JsonIndexWriter in index-writers.xml.");
        }
        return List.of(values);
    }

    @Override
    public void delete(String key) throws IOException {
        // deletion of documents not supported
        // maybe we search through the file and delete?
    }

    @Override
    public void update(NutchDocument doc) throws IOException {
        write(doc);
    }

    @Override
    public void write(NutchDocument doc) throws IOException {
        JSONObject obj = new JSONObject();

        boolean largeRecord = false;

        for (String singleFieldName : singleFields) {
            NutchField field = doc.getField(singleFieldName);
            if (field == null) {
                obj.put(singleFieldName, null);
            } else {
                Object valueToWrite = field.getValues().get(0);

                if(singleFieldName.equals("binaryContent") &&
                    binaryContentLargeCutoffChars > -1 && ((String) valueToWrite).length() > binaryContentLargeCutoffChars) {
                        largeRecord = true;
                }

                obj.put(singleFieldName, valueToWrite);
            }
        }

        for (String arrayFieldName : arrayFields) {
            NutchField field = doc.getField(arrayFieldName);
            if (field == null) {
                obj.put(arrayFieldName, new ArrayList<String>(0));
            } else {
                List<Object> values = field.getValues();
                obj.put(arrayFieldName, values);
            }
        }

        DataOutputStream streamToWrite = openJsonStream.getJsonOut();
        OpenJsonStream openJsonStream = null;
        if(largeRecord) {
            openJsonStream = createStream(buildLargeOutputPath());
            streamToWrite = openJsonStream.getJsonOut();
        }

        // JSONStyle.FLAG_PROTECT_4WEB ensures that forward-slashes are NOT escaped so that URLs
        // are easier to read
        streamToWrite.write(obj.toJSONString(new JSONStyle(JSONStyle.FLAG_PROTECT_4WEB)).getBytes(ENCODING));
        streamToWrite.write("\r\n".getBytes(ENCODING));

        if(largeRecord) {
            if(null == openJsonStream) {
                throw new IOException("Somehow large record is true but no stream is open to write to. This should never happen.");
            } else {
                closeStream(openJsonStream);
            }
        }
    }

    @Override
    public void close() throws IOException {
        closeStream(openJsonStream);
    }

    private void closeStream(OpenJsonStream openStream) throws IOException {
        // On close, if we didn't write anything (an empty file), we delete the file
        // since empty files break Athena schema recognition
        boolean isEmpty = openStream.getJsonOut().size() == 0;

        openStream.getJsonOut().close();

        if (isEmpty) {
            LOG.info("Empty file detected, removing: " + openStream.getOutFile().toString());
            FileSystem fs = buildOutputPath().getFileSystem(config);
            if (fs.exists(openStream.getOutFile())) {
                fs.delete(openStream.getOutFile(), true);
            }
        }
    }

    @Override
    public void commit() throws IOException {
        // nothing to do
    }

    @Override
    public Configuration getConf() {
        return config;
    }

    @Override
    public void setConf(Configuration conf) {
        config = conf;
    }

    /**
     * Returns {@link Map} with the specific parameters the IndexWriter instance can take.
     *
     * @return The values of each row. It must have the form &#60;KEY,&#60;DESCRIPTION,VALUE&#62;&#62;.
     */
    @Override
    public Map<String, Map.Entry<String, Object>> describe() {
        Map<String, Map.Entry<String, Object>> properties = new LinkedHashMap<>();

        properties.put(JsonConstants.SINGLE_FIELDS, new AbstractMap.SimpleEntry<>(
                "List of single-valued fields (columns) in the JSON file",
                String.join(",", this.singleFields)));
        properties.put(JsonConstants.ARRAY_FIELDS, new AbstractMap.SimpleEntry<>(
                "List of array fields (columns) in the JSON file", String.join(",", this.arrayFields)));

        properties.put(JsonConstants.JSON_BASE_OUTPUT_PATH, new AbstractMap.SimpleEntry<>(
                "The base output path for the data. Must be specified. We add partition information to the end.",
                this.baseOutputPath));
        properties.put(JsonConstants.LARGE_JSON_BASE_OUTPUT_PATH, new AbstractMap.SimpleEntry<>(
            "The base output path for the 'large' data. Optional. We add partition information to the end.",
            this.largeBaseOutputPath));
        properties.put(JsonConstants.BINARY_CONTENT_LENGTH_LARGE_CUTOFF, new AbstractMap.SimpleEntry<>(
            "The cut off in characters for a record to be routed to the large output path. (negative numbers disable this). If this is enabled, then large_base_output_path must be specified.",
            this.binaryContentLargeCutoffChars));

        return properties;
    }

    private Path buildOutputPath() {
        return new Path(String.format("%s/%s/", baseOutputPath, DATE_PARTITION_FORMAT.format(new Date())));
    }

    private Path buildLargeOutputPath() {
        return new Path(String.format("%s/%s/", largeBaseOutputPath, DATE_PARTITION_FORMAT.format(new Date())));
    }

    private boolean shouldCompressFile() {
        return StringUtils.isNotBlank(compress) && !compress.equals(JsonConstants.NO_COMPRESS);
    }

    public static void main(String[] args) throws Exception {
        final int res = ToolRunner.run(NutchConfiguration.create(),
                new IndexingJob(), args);
        System.exit(res);
    }
}
