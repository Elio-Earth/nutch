# Apache Nutch README

<img src="https://nutch.apache.org/assets/img/nutch_logo_tm.png" align="right" width="300" />

For the latest information about Nutch, please visit our website at:

   https://nutch.apache.org/

and our wiki, at:

   https://cwiki.apache.org/confluence/display/NUTCH/Home

To get started using Nutch read Tutorial:

   https://cwiki.apache.org/confluence/display/NUTCH/NutchTutorial

# Running Locally

Go to the directory.
```bash
cd $WORKSPACE_HOME/external/nutch
```

Build the project (this will create the runtime) folder.

```bash
ant clean runtime
```

Create a directory for the crawl
```bash
mkdir crawl
```

Create seed file
```bash
echo "https://nutch.apache.org/" > crawl/seed.txt
```

(Optional) Edit crawl file to reduce steps. I often remove the linking and deduping steps near the end.
```bash
mate runtime/local/bin/crawl
```

Ensure you have minimum settings in nutch-site.xml.
```bash
mate runtime/local/conf/nutch-site.xml
```

Add the http.agent.name key to nutch-site.xml.
```xml
<property>
  <name>http.agent.name</name>
  <value>etesting</value>
  <description>
  </description>
</property>
```

(Optional) You might want to adjust output locations for the index writers in.

```bash
mate runtime/local/conf/index-writers.xml
```


Run the job (1 iteration)
```bash
runtime/local/bin/crawl -i -s crawl/seed.txt -D plugin.includes='indexer-json|index-basic|protocol-http|parse-html' crawl 1
```

# Contributing

To contribute a patch, follow these instructions (note that installing
[Hub](https://hub.github.com/) is not strictly required, but is recommended).

```
0. Download and install hub.github.com
1. File JIRA issue for your fix at https://issues.apache.org/jira/projects/NUTCH/issues
- you will get issue id NUTCH-xxx where xxx is the issue ID.
2. git clone https://github.com/apache/nutch.git
3. cd nutch
4. git checkout -b NUTCH-xxx
5. edit files (please try and include a test case if possible)
6. git status (make sure it shows what files you expected to edit)
7. Make sure that your code complies with the [Nutch codeformatting template](https://raw.githubusercontent.com/apache/nutch/master/eclipse-codeformat.xml), which is basially two space indents
8. git add <files>
9. git commit -m “fix for NUTCH-xxx contributed by <your username>”
10. git fork
11. git push -u <your git username> NUTCH-xxx
12. git pull-request
```

IDE setup
=========

Ensure you have Java 11 Installed. You can see latest versions [here](https://www.oracle.com/java/technologies/downloads/#java11).

Install ANT 1.10.12 (Followed: https://ant.apache.org/manual/install.html)

```bash
cd $ANT_HOME
ant -f fetch.xml -Ddest=system
```

### Eclipse

Generate Eclipse project files

```
ant eclipse
```

and follow the instructions in [Importing existing projects](https://help.eclipse.org/2019-06/topic/org.eclipse.platform.doc.user/tasks/tasks-importproject.htm).

You must [configure the nutch-site.xml](https://cwiki.apache.org/confluence/display/NUTCH/RunNutchInEclipse) before running. Make sure, you've added ```http.agent.name``` and ```plugin.folders``` properties. The plugin.folders normally points to ```<project_root>/build/plugins```.

Now create a Java Application Configuration, choose org.apache.nutch.crawl.Injector, add two paths as arguments. First one is the crawldb directory, second one is the URL directory where, the injector can read urls. Now run your configuration.

### Intellij IDEA

First install the [IvyIDEA Plugin](https://plugins.jetbrains.com/plugin/3612-ivyidea). then run ```ant eclipse```. This will create the necessary
.classpath and .project files so that Intellij can import the project in the next step.

In Intellij IDEA, select File > New > Project from Existing Sources. Select the nutch home directory and click "Open". On the "Import Project" screen select the
"Import project from external model" radio button and select "Eclipse". Click "Create". On the next screen the "Eclipse projects directory" should be already set to
the nutch folder. Leave the "Create module files near .classpath files" radio button selected. Click "Next" on the next screens. On the project SDK screen select
a Java 11 SDK and click "Create".

Once the project is imported, you might get a popup asking you if you want to re-use "nutch.eml". Click Yes. You will
see a popup saying "Ant build scripts found". Click "Add Ant build file". If you don't get the pop-up, I'd suggest going through
the steps again as this happens from time to time. There is another Ant popup that says "Frameworks detected - IvyIDEA Framework detected"
that asks you to configure the project. Do NOT click "Configure". If you get a pop-up asking if you want to re-use
nutch.eml, click No.

**Note**: If you are getting import errors it probably means that library paths are "broken". This happens if you import the project and the build
directory that is generated while running "ant eclipse" is not present. You can confirm this by opening up the nutch.iml file in the root of the
project directory and seeing if the lib elements look like:

```xml
<libelement value="file://build/lib/commons-math3-3.1.1.jar!/" />
```

They should look like:

```xml
<libelement value="jar://$MODULE_DIR$/build/lib/commons-math3-3.1.1.jar!/" />
```

If this happens you need to run ant eclipse and re-import the project.

To import the code-style, Go to Intellij IDEA > Preferences > Editor > Code Style > Java. For the Scheme dropdown
select "Project". Click the gear icon and select "Import Scheme" > "Eclipse XML file". Select the eclipse-format.xml
file and click "Open". On next screen check the "Current Scheme" checkbox and hit OK.

There are also Run Configurations saved in .backupidea/workspace.xml. Copy the entire "<component name="RunManager"\>
tag into your .idea/workspace.xml (make sure you don't already have a RunManager component).

### Running in Intellij IDEA

Running in Intellij

- Open Run/Debug Configurations
- Select "+" to create a new configuration and select "Application"
- For "Main Class" enter a class with a main function (e.g. org.apache.nutch.indexer.IndexingJob).
- For "Program Arguments" add the arguments needed for the class. You can get these by running the crawl executable for your job. Use full-qualified paths. (e.g. /Users/kamil/workspace/external/nutch/crawl/crawldb /Users/kamil/workspace/external/nutch/crawl/segments/20221222160141 -deleteGone)
- For "Working Directory" enter "/Users/kamil/workspace/external/nutch/runtime/local".
- Select "Modify options" > "Modify Classpath" and add the config directory belonging to the "Working Directory" from the previous step (e.g. /Users/kamil/workspace/external/nutch/runtime/local/conf). This will allow the resource loader to load that configuration.
- Select "Modify options" > "Add VM Options". Add the VM options needed. You can get these by running the crawl executable for your job (e.g. -Xmx4096m -Dhadoop.log.dir=/Users/kamil/workspace/external/nutch/runtime/local/logs -Dhadoop.log.file=hadoop.log -Dmapreduce.job.reduces=2 -Dmapreduce.reduce.speculative=false -Dmapreduce.map.speculative=false -Dmapreduce.map.output.compress=true)

**Note**: You will need to manually trigger a build through ANT to get latest updated changes when running. This is because the ant build system is separate from the Intellij one.

**Note**: If you get an error saying that org.apache.lucene.analysis.standard.StandardAnalyzer.STOP_WORDS_SET does not exist you need to edit the nutch.iml to put

```xml
<orderEntry type="module-library">
  <library name="lucene-analyzers-common-6.4.1.jar">
    <CLASSES>
      <root url="jar://$MODULE_DIR$/build/plugins/scoring-similarity/lucene-analyzers-common-6.4.1.jar!/" />
    </CLASSES>
    <JAVADOC />
    <SOURCES />
  </library>
</orderEntry>
```

at the top of the orderEntry's (needs to be above lucene-analyzers-common-8.8.2.jar). And do a clean and re-build. This is because STOP_WORDS_SET was removed in lucene-analyzers-common-8.8.2.jar and the resolution of libraries is not working properly in Intellij. You also might need to move 

**Note**: If you get errors that org.junit doesn't exist you need to run the "resolve-test" target.