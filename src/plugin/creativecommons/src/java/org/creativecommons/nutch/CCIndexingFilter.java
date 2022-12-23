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
package org.creativecommons.nutch;

import org.apache.nutch.metadata.CreativeCommons;

import org.apache.nutch.parse.Parse;

import org.apache.nutch.indexer.IndexingFilter;
import org.apache.nutch.indexer.IndexingException;
import org.apache.nutch.indexer.NutchDocument;
import org.apache.hadoop.io.Text;

import org.apache.nutch.crawl.CrawlDatum;
import org.apache.nutch.crawl.Inlinks;
import org.apache.nutch.metadata.Metadata;

import org.apache.hadoop.conf.Configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.net.MalformedURLException;

/** Adds basic searchable fields to a document. */
public class CCIndexingFilter implements IndexingFilter {
  private static final Logger LOG = LoggerFactory
      .getLogger(MethodHandles.lookup().lookupClass());

  /** The name of the document field we use. */
  private static final String NAMESPACE = "cc";
  private static final String URL_KEY = "url";
  private static final String LICENSE_KEY = "license";
  private static final String VERSION_KEY = "version";
  private static final String META_KEY = "meta";
  private static final String TYPE_KEY = "type";
  private static final String FEATURES = "features";



  private Configuration conf;

  @Override
  public NutchDocument filter(NutchDocument doc, Parse parse, Text url,
      CrawlDatum datum, Inlinks inlinks) throws IndexingException {

    Metadata metadata = parse.getData().getParseMeta();
    // index the license
    String licenseUrl = metadata.get(CreativeCommons.LICENSE_URL);
    if (licenseUrl != null) {
      if (LOG.isInfoEnabled()) {
        LOG.info("CC: indexing " + licenseUrl + " for: " + url.toString());
      }

      // add the entire license URL as cc:url=xxx
      addField(doc, URL_KEY, licenseUrl);

      // index license attributes extracted of the license url
      addFieldsFromUrl(doc, licenseUrl);
    }

    // index the license location as cc:meta=xxx
    String licenseLocation = metadata.get(CreativeCommons.LICENSE_LOCATION);
    if (licenseLocation != null) {
      addField(doc, META_KEY, licenseLocation);
    }

    // index the work type cc:type=xxx
    String workType = metadata.get(CreativeCommons.WORK_TYPE);
    if (workType != null) {
      addField(doc, TYPE_KEY, workType);
    }

    return doc;
  }

  /**
   * Add the features represented by a license URL. Urls are of the form
   * "http://creativecommons.org/licenses/xx-xx/xx", where "xx" names a
   * license feature.
   * @param doc a {@link org.apache.nutch.indexer.NutchDocument} to augment
   * @param urlString the url to extract features from
   */
  public void addFieldsFromUrl(NutchDocument doc, String urlString) {
    try {
      URL url = new URL(urlString);

      String[] pathParts = url.getPath().split("/");

      if(pathParts.length != 4) {
        // we start at 2 since index 0 is empty and
        // index 1 is the "licenses" path segment
        addField(doc, LICENSE_KEY, pathParts[2]);
        for (String feature : pathParts[2].split("-")) {
          addField(doc, FEATURES, feature);
        }
        addField(doc, VERSION_KEY, pathParts[3]);
      } else {
        LOG.warn("Unknown format for CC license URL: " + urlString);
      }
    } catch (MalformedURLException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("CC: failed to parse url: " + urlString + " : " + e);
      }
    }
  }

  private void addField(NutchDocument doc, String key, String value) {
    doc.add(NAMESPACE + ":" + key, value);
  }

  @Override
  public void setConf(Configuration conf) {
    this.conf = conf;
  }

  @Override
  public Configuration getConf() {
    return this.conf;
  }

}
