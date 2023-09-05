package earth.elio.nutch.indexwriter.json;

import org.apache.hadoop.fs.Path;

import java.io.DataOutputStream;

public class OpenJsonStream {

  /** The file that we write out to. */
  protected Path outFile;
  /** The stream to the file */
  protected DataOutputStream jsonOut;

  public OpenJsonStream(DataOutputStream jsonOut, Path outFile) {
    this.jsonOut = jsonOut;
    this.outFile = outFile;
  }

  public DataOutputStream getJsonOut() {
    return jsonOut;
  }

  public Path getOutFile() {
    return outFile;
  }
}
