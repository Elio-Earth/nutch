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
package org.apache.nutch.util;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.MissingOptionException;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.GzipCodec;
import org.apache.hadoop.mapreduce.*;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.nutch.crawl.CrawlDatum;
import org.apache.nutch.metadata.Nutch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.hadoop.mapreduce.lib.output.TextOutputFormat.SEPARATOR;

/**
 * Extracts a summary of the crawl stats from the crawldb. Outputs counts by
 * host/domain, crawl status and HTTP code
 *
 * Stats will be sorted by host/domain and will be of the form:
 * www.nhl.com, db_fetched 200 10
 * www.nhl.com, db_fetched 404 1
 * www.nhl.com, db_unfetched NA 1
 * www.nhl.com, db_fetched 200 10
 * www.nba.com, db_unfetched NA 5
 *
 */
public class CrawlSummaryStats extends Configured implements Tool {

  private static final Logger LOG = LoggerFactory
      .getLogger(MethodHandles.lookup().lookupClass());

  @Override
  public int run(String[] args) throws Exception {
    Option helpOpt = new Option("h", "help", false, "Show this message");
    @SuppressWarnings("static-access")
    Option inDirs = OptionBuilder
        .withArgName("inputDirs")
        .isRequired()
        .withDescription("Comma separated list of crawldb directories (e.g., \"./crawl1/crawldb,./crawl2/crawldb\")")
        .hasArgs()
        .create("inputDirs");
    @SuppressWarnings("static-access")
    Option outDir = OptionBuilder
        .withArgName("outputDir")
        .isRequired()
        .withDescription("Output directory where results should be dumped")
        .hasArgs()
        .create("outputDir");
    @SuppressWarnings("static-access")
    Option numReducers = OptionBuilder
        .withArgName("numReducers")
        .withDescription("Optional number of reduce jobs to use. Defaults to 1")
        .hasArgs()
        .create("numReducers");

    Options options = new Options();
    options.addOption(helpOpt);
    options.addOption(inDirs);
    options.addOption(outDir);
    options.addOption(numReducers);

    CommandLineParser parser = new GnuParser();
    CommandLine cli;

    try {
      cli = parser.parse(options, args);
    } catch (MissingOptionException e) {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("CrawlSummaryStats", options, true);
      return 1;
    }

    if (cli.hasOption("help")) {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("CrawlSummaryStats", options, true);
      return 1;
    }

    String inputDir = cli.getOptionValue("inputDirs");
    String outputDir = cli.getOptionValue("outputDir");

    int numOfReducers = 1;
    if (cli.hasOption("numReducers")) {
      numOfReducers = Integer.parseInt(args[3]);
    }

    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    long start = System.currentTimeMillis();
    LOG.info("CrawlSummaryStats: starting at {}", sdf.format(start));

    int mode = 0;
    String jobName = "CrawlSummaryStats";

    Configuration conf = getConf();
    conf.setInt("domain.statistics.mode", mode);
    conf.setBoolean("mapreduce.fileoutputcommitter.marksuccessfuljobs", false);
    conf.set(SEPARATOR, ",");

    Job job = Job.getInstance(conf, jobName);
    job.setJarByClass(CrawlSummaryStats.class);

    String[] inputDirsSpecs = inputDir.split(",");
    for (int i = 0; i < inputDirsSpecs.length; i++) {
      FileInputFormat.addInputPath(job, new Path(inputDirsSpecs[i], "current"));
    }

    job.setInputFormatClass(SequenceFileInputFormat.class);
    FileOutputFormat.setOutputPath(job, new Path(outputDir));
    job.setOutputFormatClass(DomainCrawlSummaryJsonOutputFormat.class);

    job.setMapOutputKeyClass(Text.class);
    job.setMapOutputValueClass(DomainCrawlSummaryWritable.class);
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(DomainCrawlSummaryWritable.class);

    job.setMapperClass(CrawlSummaryStatsMapper.class);
    job.setCombinerClass(CrawlSummaryStatsCombiner.class);
    job.setReducerClass(CrawlSummaryStatsReducer.class);
    job.setNumReduceTasks(numOfReducers);

    try {
      boolean success = job.waitForCompletion(true);
      if (!success) {
        String message = NutchJob.getJobFailureLogMessage(jobName, job);
        LOG.error(message);
        // throw exception so that calling routine can exit with error
        throw new RuntimeException(message);
      }
    } catch (IOException | InterruptedException | ClassNotFoundException e) {
      LOG.error(jobName + " job failed");
      throw e;
    }

    long end = System.currentTimeMillis();
    LOG.info("CrawlSummaryStats: finished at {}, elapsed: {}",
        sdf.format(end), TimingUtil.elapsedTime(start, end));
    return 0;
  }

  static class CrawlSummaryStatsMapper extends
      Mapper<Text, CrawlDatum, Text, DomainCrawlSummaryWritable> {

    @Override
    public void map(Text urlText, CrawlDatum datum, Context context)
        throws IOException, InterruptedException {

      URL url;
      try {
        url = new URL(urlText.toString());
      } catch (MalformedURLException e) {
        LOG.error("Failed to get host or domain from URL {}: {}",
            urlText, e.getMessage());
        return;
      }
      String host = url.getHost();

      int httpStatusCode = -1;
      if (datum.getMetaData().containsKey(Nutch.PROTOCOL_STATUS_CODE_KEY)) {
        httpStatusCode = Integer.parseInt(
            datum.getMetaData().get(Nutch.PROTOCOL_STATUS_CODE_KEY).toString()
        );
      }

      DomainCrawlSummaryWritable line = new DomainCrawlSummaryWritable(
          host,
          CrawlDatum.statNames.get(datum.getStatus()),
          httpStatusCode
      );

      context.write(new Text(line.key()), line);
    }
  }

  public static class CrawlSummaryStatsCombiner extends
      Reducer<Text, DomainCrawlSummaryWritable, Text, DomainCrawlSummaryWritable> {
    @Override
    public void reduce(Text key, Iterable<DomainCrawlSummaryWritable> values, Context context)
        throws IOException, InterruptedException {
      long total = 0;

      DomainCrawlSummaryWritable other = null;
      for (DomainCrawlSummaryWritable val : values) {
        other = val;
        total += val.getCount();
      }

      if (other != null) {
        context.write(key, new DomainCrawlSummaryWritable(other, total));
      }
    }
  }

  static class CrawlSummaryStatsReducer extends
      Reducer<Text, DomainCrawlSummaryWritable, Text, DomainCrawlSummaryWritable> {
    @Override
    public void reduce(Text key, Iterable<DomainCrawlSummaryWritable> values, Context context)
        throws IOException, InterruptedException {
      long total = 0;

      DomainCrawlSummaryWritable other = null;
      for (DomainCrawlSummaryWritable val : values) {
        other = val;
        total += val.getCount();
      }

      if (other != null) {
        context.write(key, new DomainCrawlSummaryWritable(other, total));
      }
    }
  }

  public static class DomainCrawlSummaryWritable implements Writable {

    private String host;
    private String nutchStatus;
    private int httpStatus;
    private long count;

    public DomainCrawlSummaryWritable() {
      nutchStatus = "unknown";
      httpStatus = -1;
      count = 0;
    }

    public DomainCrawlSummaryWritable(String host, String nutchStatus, int httpStatus) {
      this.host = host;
      this.nutchStatus = nutchStatus;
      this.httpStatus = httpStatus;
      this.count = 1;
    }

    public DomainCrawlSummaryWritable(
        DomainCrawlSummaryWritable other,
        long count
    ) {
      this.host = other.getHost();
      this.nutchStatus = other.getNutchStatus();
      this.httpStatus = other.getHttpStatus();
      this.count = count;
    }

    public String getHost() {
      return host;
    }

    public String getNutchStatus() {
      return nutchStatus;
    }

    public int getHttpStatus() {
      return httpStatus;
    }

    public long getCount() {
      return count;
    }

    public String key() {
      return host + nutchStatus + httpStatus;
    }

    @Override
    public void write(DataOutput dataOutput) throws IOException {
      Text.writeString(dataOutput, host);
      Text.writeString(dataOutput, nutchStatus);
      dataOutput.writeInt(httpStatus);
      dataOutput.writeLong(count);
    }

    @Override
    public void readFields(DataInput dataInput) throws IOException {
      host = Text.readString(dataInput);
      nutchStatus = Text.readString(dataInput);
      httpStatus = dataInput.readInt();
      count = dataInput.readLong();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o)
        return true;
      if (o == null || getClass() != o.getClass())
        return false;
      DomainCrawlSummaryWritable that = (DomainCrawlSummaryWritable) o;
      return httpStatus == that.httpStatus && count == that.count && host.equals(
          that.host) && nutchStatus.equals(that.nutchStatus);
    }

    @Override
    public int hashCode() {
      return Objects.hash(host, nutchStatus, httpStatus, count);
    }

    @Override
    public String toString() {
      return host + "," + nutchStatus + "," + httpStatus + "," + count;
    }
  }

  public static class DomainCrawlSummaryJsonOutputFormat extends FileOutputFormat<Text, DomainCrawlSummaryWritable> {
    protected static class LineRecordWriter extends RecordWriter<Text, DomainCrawlSummaryWritable> {
      private final DataOutputStream out;
      private final ObjectMapper jsonMapper;
      private final ObjectWriter jsonWriter;

      public LineRecordWriter(DataOutputStream out) {
        this.out = out;
        jsonMapper = new ObjectMapper();
        jsonWriter = jsonMapper.writer();
      }

      @Override
      public synchronized void write(Text key, DomainCrawlSummaryWritable value) throws IOException {
        Map<String, Object> data = new HashMap<String, Object>();

        data.put("host", value.getHost());
        data.put("nutch_status", value.getNutchStatus());
        data.put("http_status_code", value.getHttpStatus());
        data.put("count", value.getCount());

        out.write(jsonWriter.writeValueAsBytes(data));
        out.writeByte('\n');
      }

      @Override
      public synchronized void close(TaskAttemptContext context) throws IOException {
        out.close();
      }
    }

    @Override
    public RecordWriter<Text, DomainCrawlSummaryWritable> getRecordWriter(
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

  public static void main(String[] args) throws Exception {
    ToolRunner.run(NutchConfiguration.create(), new CrawlSummaryStats(), args);
  }
}
