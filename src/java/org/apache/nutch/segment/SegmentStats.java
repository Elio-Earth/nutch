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
package org.apache.nutch.segment;

import java.io.*;
import java.lang.invoke.MethodHandles;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.GzipCodec;
import org.apache.hadoop.mapreduce.*;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.nutch.metadata.Nutch;
import org.apache.nutch.parse.ParseStatus;
import org.apache.nutch.protocol.ProtocolStatus;
import org.apache.nutch.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.nutch.crawl.CrawlDatum;
import org.apache.nutch.crawl.NutchWritable;
import org.apache.nutch.parse.ParseData;

/** Dump the content of a segment. */
public class SegmentStats extends Configured implements Tool {

    private static final Logger LOG = LoggerFactory
            .getLogger(MethodHandles.lookup().lookupClass());

    public static class SegmentStatsMapper extends
            Mapper<WritableComparable<?>, Writable, Text, NutchWritable> {

        private final Text newKey = new Text();

        @Override
        public void map(WritableComparable<?> key, Writable value,
                        Context context) throws IOException, InterruptedException {
            // convert on the fly from old formats with UTF8 keys.
            // UTF8 deprecated and replaced by Text.
            if (key instanceof Text) {
                newKey.set(key.toString());
                key = newKey;
            }
            context.write((Text) key, new NutchWritable(value));
        }
    }

    public static class SegmentStatsReducer extends Reducer<Text, NutchWritable, Text, SegmentStatsWritable> {

        @Override
        public void reduce(Text key, Iterable<NutchWritable> values,
                           Context context) throws IOException, InterruptedException {

            SegmentStatsWritable writable = new SegmentStatsWritable(key.toString());
            for (NutchWritable val : values) {
                Writable value = val.get(); // unwrap
                if (value instanceof CrawlDatum) {
                    // this can contain either crawl_generate or crawl_fetch information so we check for valid values
                    CrawlDatum crawlDatum = (CrawlDatum) value;

                    if (crawlDatum.getStatus() > writable.getNutchStatus()) {
                        writable.setNutchStatus(crawlDatum.getStatus());
                    }

                    if (crawlDatum.getFetchTime() > 0) {
                        writable.setFetchTime(crawlDatum.getFetchTime());
                    }

                    if (crawlDatum.getMetaData().containsKey(Nutch.WRITABLE_PROTO_STATUS_KEY)) {
                        writable.setProtocolStatus(
                                ((ProtocolStatus) crawlDatum.getMetaData().get(Nutch.WRITABLE_PROTO_STATUS_KEY)).getCode()
                        );
                    }

                    int httpStatusCode = -1;
                    if (crawlDatum.getMetaData().containsKey(Nutch.PROTOCOL_STATUS_CODE_KEY)) {
                        httpStatusCode = Integer.parseInt(
                                crawlDatum.getMetaData().get(Nutch.PROTOCOL_STATUS_CODE_KEY).toString()
                        );
                        writable.setHttpStatus(httpStatusCode);
                    }

                    if (crawlDatum.getFetchInterval() > 0) {
                        writable.setFetchInterval(crawlDatum.getFetchInterval());
                    }

                    if (crawlDatum.getMetaData().containsKey(Nutch.FETCH_EXCEPTION_KEY)) {
                        writable.setFetchException(crawlDatum.getMetaData().get(Nutch.FETCH_EXCEPTION_KEY).toString());
                    }
                } else if (value instanceof ParseData) {
                    ParseData parseData = (ParseData) value;
                    writable.setParseStatus(parseData.getStatus().getMajorCode());

                    if (org.apache.commons.lang.StringUtils.isNotBlank(parseData.getStatus().getMessage())) {
                       writable.setParseException(parseData.getStatus().getMessage());
                    }
                } else if (LOG.isWarnEnabled()) {
                    LOG.warn("Unrecognized type: " + value.getClass());
                }
            }

            context.write(key, writable);
        }
    }

    public void segmentStats(Path segment, Path output) throws IOException, InterruptedException, ClassNotFoundException {

        LOG.info("SegmentStats: start segment {}", segment);

        Job job = NutchJob.getInstance(getConf());
        job.setJobName("SegmentStats " + segment);

        FileInputFormat.addInputPath(job, new Path(segment, CrawlDatum.GENERATE_DIR_NAME));
        FileInputFormat.addInputPath(job, new Path(segment, CrawlDatum.FETCH_DIR_NAME));
        FileInputFormat.addInputPath(job, new Path(segment, ParseData.DIR_NAME));

        job.setJarByClass(SegmentStats.class);
        job.setInputFormatClass(SequenceFileInputFormat.class);

        job.setMapperClass(SegmentStatsMapper.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(NutchWritable.class);

        job.setReducerClass(SegmentStatsReducer.class);
        FileOutputFormat.setOutputPath(job, output);
        job.setOutputFormatClass(SegmentStatsJsonOutputFormat.class);

        try {
            boolean success = job.waitForCompletion(true);
            if (!success) {
                String message = NutchJob.getJobFailureLogMessage("SegmentStats", job);
                LOG.error(message);
                throw new RuntimeException(message);
            }
        } catch (IOException | InterruptedException | ClassNotFoundException e ){
            LOG.error(StringUtils.stringifyException(e));
            throw e;
        }

        LOG.info("SegmentStats: done");
    }

    @Override
    public int run(String[] args) throws Exception {
        if (args.length != 2) {
            usage();
            return -1;
        }

        String segmentDir = args[0];
        if (segmentDir == null) {
            System.err.println("Missing required argument: <segment_dir>");
            usage();
            return -1;
        }

        String outputDir = args[1];
        if (outputDir == null) {
            System.err.println("Missing required argument: <output_dir>");
            usage();
            return -1;
        }
        segmentStats(new Path(segmentDir), new Path(outputDir));
        return 0;
    }

    private static void usage() {
        System.err.println("Usage: SegmentStats <segment_dir> <output_dir>\n");
        System.err.println("\t<segment_dir>\tname of the segment directory.");
        System.err.println("\t<output_dir>\tname of the (non-existent) output directory.");
        System.err.println();
        System.err.println("\t\tNote: put double-quotes around strings with spaces.");
    }

    public static void main(String[] args) throws Exception {
        int result = ToolRunner.run(NutchConfiguration.create(), new SegmentStats(), args);
        System.exit(result);
    }

    public static class SegmentStatsWritable implements Writable {

        private String url;
        private byte nutchStatus;
        private int protocolStatus;
        private int httpStatus;
        private int fetchInterval;
        private long fetchTime;
        private String fetchException;
        private int parseStatus;
        private String parseException;

        public SegmentStatsWritable() {
            this.url = "";
            this.nutchStatus = 0x0;
            this.protocolStatus = -1;
            this.httpStatus = -1;
            this.fetchInterval = -1;
            this.fetchTime = -1;
            this.fetchException = null;
            this.parseStatus = -1;
            this.parseException = null;
        }

        public SegmentStatsWritable(String url) {
            this.url = url;
            this.protocolStatus = -1;
            this.nutchStatus = 0x0;
            this.httpStatus = -1;
            this.fetchInterval = -1;
            this.fetchTime = -1;
            this.fetchException = null;
            this.parseStatus = -1;
            this.parseException = null;
        }

        public String getUrl() {
            return url;
        }

        public String getProtocolStatusName() {
            if (protocolStatus > 0) {
                if (ProtocolStatus.codeToName.containsKey(protocolStatus)) {
                    return ProtocolStatus.codeToName.get(protocolStatus);
                } else {
                    return Integer.toString(protocolStatus);
                }
            } else {
                return "unknown";
            }
        }

        public int getHttpStatus() {
            return httpStatus;
        }

        public int getFetchInterval() {
            return fetchInterval;
        }

        public long getFetchTime() {
            return fetchTime;
        }

        public String getFetchException() {
            return fetchException;
        }

        public String getParseStatusName() {
            if (parseStatus > 0) {
                return ParseStatus.majorCodes[parseStatus];
            } else {
                return "unknown";
            }
        }

        public String getParseException() {
            return parseException;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public byte getNutchStatus() {
            return nutchStatus;
        }

        public String getNutchStatusName() {
            if (nutchStatus > 0) {
                return CrawlDatum.getStatusName(nutchStatus);
            } else {
                return "unknown";
            }
        }


        public void setNutchStatus(byte nutchStatus) {
            this.nutchStatus = nutchStatus;
        }

        public void setProtocolStatus(int protocolStatus) {
            this.protocolStatus = protocolStatus;
        }

        public void setHttpStatus(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        public void setFetchInterval(int fetchInterval) {
            this.fetchInterval = fetchInterval;
        }

        public void setFetchTime(long fetchTime) {
            this.fetchTime = fetchTime;
        }

        public void setFetchException(String fetchException) {
            this.fetchException = fetchException;
        }

        public void setParseStatus(int parseStatus) {
            this.parseStatus = parseStatus;
        }

        public void setParseException(String parseException) {
            this.parseException = parseException;
        }

        @Override
        public void write(DataOutput dataOutput) throws IOException {
            Text.writeString(dataOutput, url);
            dataOutput.writeByte(nutchStatus);
            dataOutput.writeByte(protocolStatus);
            dataOutput.writeInt(httpStatus);
            dataOutput.writeLong(fetchInterval);
            dataOutput.writeLong(fetchTime);
            Text.writeString(dataOutput, fetchException);
            dataOutput.writeInt(parseStatus);
            Text.writeString(dataOutput, parseException);
        }

        @Override
        public void readFields(DataInput dataInput) throws IOException {
            url = Text.readString(dataInput);
            nutchStatus = dataInput.readByte();
            protocolStatus = dataInput.readInt();
            httpStatus = dataInput.readInt();
            fetchInterval = dataInput.readInt();
            fetchTime = dataInput.readLong();
            fetchException = Text.readString(dataInput);
            parseStatus = dataInput.readInt();
            parseException = Text.readString(dataInput);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SegmentStatsWritable that = (SegmentStatsWritable) o;
            return nutchStatus == that.nutchStatus && protocolStatus == that.protocolStatus && httpStatus == that.httpStatus && fetchInterval == that.fetchInterval && fetchTime == that.fetchTime && parseStatus == that.parseStatus && Objects.equals(url, that.url) && Objects.equals(fetchException, that.fetchException) && Objects.equals(parseException, that.parseException);
        }

        @Override
        public int hashCode() {
            return Objects.hash(url, nutchStatus, protocolStatus, httpStatus, fetchInterval, fetchTime, fetchException, parseStatus, parseException);
        }

        @Override
        public String toString() {
            String nutchStatusName = "unknown";
            if (nutchStatus > 0) {
                nutchStatusName = CrawlDatum.getStatusName(nutchStatus);
            }
            String protocolStatusName = "unknown";
            if (protocolStatus > 0) {
                if (ProtocolStatus.codeToName.containsKey(protocolStatus)) {
                    protocolStatusName = ProtocolStatus.codeToName.get(protocolStatus);
                } else {
                    protocolStatusName = Integer.toString(protocolStatus);
                }
            }
            String parseStatusName = "unknown";
            if(parseStatus > 0) {
                parseStatusName = ParseStatus.majorCodes[parseStatus];
            }
            return url + "," + nutchStatusName + "," + protocolStatusName + "," + httpStatus + ","
                    + fetchInterval + "," + fetchTime + "," + fetchException + "," + parseStatusName + "," +
                    parseException;
        }
    }

    public static class SegmentStatsJsonOutputFormat extends FileOutputFormat<Text, SegmentStatsWritable> {
        protected static class LineRecordWriter extends RecordWriter<Text, SegmentStatsWritable> {
            private final DataOutputStream out;
            private final ObjectMapper jsonMapper;
            private final ObjectWriter jsonWriter;

            public LineRecordWriter(DataOutputStream out) {
                this.out = out;
                jsonMapper = new ObjectMapper();
                jsonWriter = jsonMapper.writer();
            }

            @Override
            public synchronized void write(Text key, SegmentStatsWritable value) throws IOException {
                Map<String, Object> data = new HashMap<String, Object>();

                data.put("url", value.getUrl());
                data.put("nutch_status", value.getNutchStatusName());
                data.put("protocol_status", value.getProtocolStatusName());
                data.put("http_status_code", value.getHttpStatus());
                data.put("fetch_interval", value.getFetchInterval());
                data.put("fetch_time_ms", value.getFetchTime());
                data.put("fetch_exception", value.getFetchException());
                data.put("parse_status", value.getParseStatusName());
                data.put("parse_exception", value.getParseException());

                out.write(jsonWriter.writeValueAsBytes(data));
                out.writeByte('\n');
            }

            @Override
            public synchronized void close(TaskAttemptContext context) throws IOException {
                out.close();
            }
        }

        @Override
        public RecordWriter<Text, SegmentStatsWritable> getRecordWriter(
                TaskAttemptContext context) throws IOException {
            Configuration conf = context.getConfiguration();
            boolean isCompressed = FileOutputFormat.getCompressOutput(context);
            CompressionCodec codec = null;
            String extension = "";
            if (isCompressed) {
                Class<? extends CompressionCodec> codecClass = getOutputCompressorClass(context, GzipCodec.class);
                codec = ReflectionUtils.newInstance(codecClass, conf);
                extension = codec.getDefaultExtension();
            }
            Path file = getDefaultWorkFile(context, extension);
            FileSystem fs = file.getFileSystem(conf);
            FSDataOutputStream fileOut = fs.create(file, false);
            if (isCompressed) {
                return new LineRecordWriter(new DataOutputStream(codec.createOutputStream(fileOut)));
            } else {
                return new LineRecordWriter(fileOut);
            }
        }
    }
}
