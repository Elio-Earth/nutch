package earth.elio.nutch.indexwriter.json;

public interface JsonConstants {

    /**
     * The list of fields which will be written out as a single value. If multiple values are encountered,
     * only the first will be written out.
     */
    String SINGLE_FIELDS = "fields.single";

    /**
     * The list of fields which will be written out as an array of strings. If the value is only a
     * single value, the writer will force it to be an array.
     */
    String ARRAY_FIELDS = "fields.array";

    /**
    * The base output path for the data. Partition information is added to this path to compute
    * the final path where the data will live.
    */
    String JSON_BASE_OUTPUT_PATH = "base_output_path";

    /**
     * Whether or not to compress the output using gzip.
     */
    String COMPRESS = "compress";

    String GZIP = "gzip";
    String NO_COMPRESS = "false";

}