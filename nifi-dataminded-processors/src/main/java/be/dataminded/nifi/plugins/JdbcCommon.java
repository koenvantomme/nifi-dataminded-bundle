/*
 * Copyright 2017 Data Minded
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package be.dataminded.nifi.plugins;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.SchemaBuilder.FieldAssembler;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumWriter;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.sql.*;

import static java.sql.Types.*;

/**
 * JDBC / SQL common functions.
 */
class JdbcCommon {

    private static final int MAX_DIGITS_IN_BIGINT = 19;

    public static long convertToAvroStream(final ResultSet rs, final OutputStream outStream, boolean convertNames) throws SQLException, IOException {
        return convertToAvroStream(rs, outStream, null, null, convertNames);
    }

    public static long convertToAvroStream(final ResultSet rs, final OutputStream outStream, String recordName, boolean convertNames)
            throws SQLException, IOException {
        return convertToAvroStream(rs, outStream, recordName, null, convertNames);
    }

    public static long convertToAvroStream(final ResultSet rs, final OutputStream outStream, String recordName, ResultSetRowCallback callback, boolean convertNames)
            throws IOException, SQLException {
        return convertToAvroStream(rs, outStream, recordName, callback, 0, convertNames);
    }

    public static long convertToAvroStream(final ResultSet rs, final OutputStream outStream, String recordName, ResultSetRowCallback callback, final int maxRows, boolean convertNames)
            throws SQLException, IOException {
        final Schema schema = createSchema(rs, recordName, convertNames);
        final GenericRecord rec = new GenericData.Record(schema);

        final DatumWriter<GenericRecord> datumWriter = new GenericDatumWriter<>(schema);
        try (final DataFileWriter<GenericRecord> dataFileWriter = new DataFileWriter<>(datumWriter)) {
            dataFileWriter.create(schema, outStream);

            final ResultSetMetaData meta = rs.getMetaData();
            final int nrOfColumns = meta.getColumnCount();
            long nrOfRows = 0;
            while (rs.next()) {
                if (callback != null) {
                    callback.processRow(rs);
                }
                for (int i = 1; i <= nrOfColumns; i++) {
                    final int javaSqlType = meta.getColumnType(i);

                    // Need to handle CLOB and BLOB before getObject() is called, due to ResultSet's maximum portability statement
                    if (javaSqlType == CLOB) {
                        Clob clob = rs.getClob(i);
                        if (clob != null) {
                            long numChars = clob.length();
                            char[] buffer = new char[(int) numChars];
                            InputStream is = clob.getAsciiStream();
                            int index = 0;
                            int c = is.read();
                            while (c > 0) {
                                buffer[index++] = (char) c;
                                c = is.read();
                            }
                            rec.put(i - 1, new String(buffer));
                            clob.free();
                        } else {
                            rec.put(i - 1, null);
                        }
                        continue;
                    }

                    if (javaSqlType == BLOB) {
                        Blob blob = rs.getBlob(i);
                        if (blob != null) {
                            long numChars = blob.length();
                            byte[] buffer = new byte[(int) numChars];
                            InputStream is = blob.getBinaryStream();
                            int index = 0;
                            int c = is.read();
                            while (c > 0) {
                                buffer[index++] = (byte) c;
                                c = is.read();
                            }
                            ByteBuffer bb = ByteBuffer.wrap(buffer);
                            rec.put(i - 1, bb);
                            blob.free();
                        } else {
                            rec.put(i - 1, null);
                        }
                        continue;
                    }

                    final Object value = rs.getObject(i);

                    if (value == null) {
                        rec.put(i - 1, null);

                    } else if (javaSqlType == BINARY || javaSqlType == VARBINARY || javaSqlType == LONGVARBINARY || javaSqlType == ARRAY) {
                        // bytes requires little bit different handling
                        byte[] bytes = rs.getBytes(i);
                        ByteBuffer bb = ByteBuffer.wrap(bytes);
                        rec.put(i - 1, bb);

                    } else if (value instanceof Byte) {
                        // tinyint(1) type is returned by JDBC driver as java.sql.Types.TINYINT
                        // But value is returned by JDBC as java.lang.Byte
                        // (at least H2 JDBC works this way)
                        // direct put to avro record results:
                        // org.apache.avro.AvroRuntimeException: Unknown datum type java.lang.Byte
                        rec.put(i - 1, ((Byte) value).intValue());
                    } else if(value instanceof Short) {
                        //MS SQL returns TINYINT as a Java Short, which Avro doesn't understand.
                        rec.put(i - 1, ((Short) value).intValue());
                    } else if (value instanceof BigDecimal) {
                        // Avro can't handle BigDecimal as a number - it will throw an AvroRuntimeException such as: "Unknown datum type: java.math.BigDecimal: 38"
                        try {
                            int scale = meta.getScale(i);
                            BigDecimal bigDecimal = ((BigDecimal) value);
                            if (scale == 0) {
                                if (meta.getPrecision(i) < 10) {
                                    rec.put(i - 1, bigDecimal.intValue());
                                } else {
                                    rec.put(i - 1, bigDecimal.longValue());
                                }
                            } else {
                                rec.put(i - 1, bigDecimal.doubleValue());
                            }
                        } catch (Exception e) {
                            rec.put(i - 1, value.toString());
                        }
                    } else if (value instanceof BigInteger) {
                        // Check the precision of the BIGINT. Some databases allow arbitrary precision (> 19), but Avro won't handle that.
                        // It the SQL type is BIGINT and the precision is between 0 and 19 (inclusive); if so, the BigInteger is likely a
                        // long (and the schema says it will be), so try to get its value as a long.
                        // Otherwise, Avro can't handle BigInteger as a number - it will throw an AvroRuntimeException
                        // such as: "Unknown datum type: java.math.BigInteger: 38". In this case the schema is expecting a string.
                        if (javaSqlType == BIGINT) {
                            int precision = meta.getPrecision(i);
                            if (precision < 0 || precision > MAX_DIGITS_IN_BIGINT) {
                                rec.put(i - 1, value.toString());
                            } else {
                                try {
                                    rec.put(i - 1, ((BigInteger) value).longValueExact());
                                } catch (ArithmeticException ae) {
                                    // Since the value won't fit in a long, convert it to a string
                                    rec.put(i - 1, value.toString());
                                }
                            }
                        } else {
                            rec.put(i - 1, value.toString());
                        }

                    } else if (value instanceof Number || value instanceof Boolean) {
                        if (javaSqlType == BIGINT) {
                            int precision = meta.getPrecision(i);
                            if (precision < 0 || precision > MAX_DIGITS_IN_BIGINT) {
                                rec.put(i - 1, value.toString());
                            } else {
                                rec.put(i - 1, value);
                            }
                        } else {
                            rec.put(i - 1, value);
                        }

                    } else {
                        // The different types that we support are numbers (int, long, double, float),
                        // as well as boolean values and Strings. Since Avro doesn't provide
                        // timestamp types, we want to convert those to Strings. So we will cast anything other
                        // than numbers or booleans to strings by using the toString() method.
                        rec.put(i - 1, value.toString());
                    }
                }
                dataFileWriter.append(rec);
                nrOfRows += 1;

                if (maxRows > 0 && nrOfRows == maxRows)
                    break;
            }

            return nrOfRows;
        }
    }

    public static Schema createSchema(final ResultSet rs) throws SQLException {
        return createSchema(rs, null, false);
    }

    /**
     * Creates an Avro schema from a result set. If the table/record name is known a priori and provided, use that as a
     * fallback for the record name if it cannot be retrieved from the result set, and finally fall back to a default value.
     *
     * @param rs         The result set to convert to Avro
     * @param recordName The a priori record name to use if it cannot be determined from the result set.
     * @return A Schema object representing the result set converted to an Avro record
     * @throws SQLException if any error occurs during conversion
     */
    public static Schema createSchema(final ResultSet rs, String recordName, boolean convertNames) throws SQLException {
        final ResultSetMetaData meta = rs.getMetaData();
        final int nrOfColumns = meta.getColumnCount();
        String tableName = StringUtils.isEmpty(recordName) ? "NiFi_ExecuteSQL_Record" : recordName;
        if (nrOfColumns > 0) {
            String tableNameFromMeta = meta.getTableName(1);
            if (!StringUtils.isBlank(tableNameFromMeta)) {
                tableName = tableNameFromMeta;
            }
        }

        if (convertNames) {
            tableName = normalizeNameForAvro(tableName);
        }

        final FieldAssembler<Schema> builder = SchemaBuilder.record(tableName).namespace("any.data").fields();

        /**
         * Some missing Avro types - Decimal, Date types. May need some additional work.
         */
        for (int i = 1; i <= nrOfColumns; i++) {
        /**
        *   as per jdbc 4 specs, getColumnLabel will have the alias for the column, if not it will have the column name.
        *  so it may be a better option to check for columnlabel first and if in case it is null is someimplementation,
        *  check for alias. Postgres is the one that has the null column names for calculated fields.
        */
            String nameOrLabel = StringUtils.isNotEmpty(meta.getColumnLabel(i)) ? meta.getColumnLabel(i) :meta.getColumnName(i);
            String columnName = convertNames ? normalizeNameForAvro(nameOrLabel) : nameOrLabel;
            switch (meta.getColumnType(i)) {
                case CHAR:
                case LONGNVARCHAR:
                case LONGVARCHAR:
                case NCHAR:
                case NVARCHAR:
                case VARCHAR:
                case CLOB:
                    builder.name(columnName).type().unionOf().nullBuilder().endNull().and().stringType().endUnion().noDefault();
                    break;

                case BIT:
                case BOOLEAN:
                    builder.name(columnName).type().unionOf().nullBuilder().endNull().and().booleanType().endUnion().noDefault();
                    break;

                case INTEGER:
                    if (meta.isSigned(i)) {
                        builder.name(columnName).type().unionOf().nullBuilder().endNull().and().intType().endUnion().noDefault();
                    } else {
                        builder.name(columnName).type().unionOf().nullBuilder().endNull().and().longType().endUnion().noDefault();
                    }
                    break;

                case SMALLINT:
                case TINYINT:
                    builder.name(columnName).type().unionOf().nullBuilder().endNull().and().intType().endUnion().noDefault();
                    break;

                case BIGINT:
                    // Check the precision of the BIGINT. Some databases allow arbitrary precision (> 19), but Avro won't handle that.
                    // If the precision > 19 (or is negative), use a string for the type, otherwise use a long. The object(s) will be converted
                    // to strings as necessary
                    int precision = meta.getPrecision(i);
                    if (precision < 0 || precision > MAX_DIGITS_IN_BIGINT) {
                        builder.name(columnName).type().unionOf().nullBuilder().endNull().and().stringType().endUnion().noDefault();
                    } else {
                        builder.name(columnName).type().unionOf().nullBuilder().endNull().and().longType().endUnion().noDefault();
                    }
                    break;

                // java.sql.RowId is interface, is seems to be database
                // implementation specific, let's convert to String
                case ROWID:
                    builder.name(columnName).type().unionOf().nullBuilder().endNull().and().stringType().endUnion().noDefault();
                    break;

                case FLOAT:
                case REAL:
                    builder.name(columnName).type().unionOf().nullBuilder().endNull().and().floatType().endUnion().noDefault();
                    break;

                case DOUBLE:
                    builder.name(columnName).type().unionOf().nullBuilder().endNull().and().doubleType().endUnion().noDefault();
                    break;

                // Did not find direct suitable type, need to be clarified!!!!
                case DECIMAL:
                case NUMERIC:
                    int scale = meta.getScale(i);
                    if (scale == 0) {
                        if (meta.getPrecision(i) < 10) {
                            builder.name(columnName).type().unionOf().nullBuilder().endNull().and().intType().endUnion().noDefault();
                        } else {
                            builder.name(columnName).type().unionOf().nullBuilder().endNull().and().longType().endUnion().noDefault();
                        }
                    } else {
                        builder.name(columnName).type().unionOf().nullBuilder().endNull().and().doubleType().endUnion().noDefault();
                    }
                    break;

                // Did not find direct suitable type, need to be clarified!!!!
                case DATE:
                case TIME:
                case TIMESTAMP:
                    builder.name(columnName).type().unionOf().nullBuilder().endNull().and().stringType().endUnion().noDefault();
                    break;

                case BINARY:
                case VARBINARY:
                case LONGVARBINARY:
                case ARRAY:
                case BLOB:
                    builder.name(columnName).type().unionOf().nullBuilder().endNull().and().bytesType().endUnion().noDefault();
                    break;


                default:
                    throw new IllegalArgumentException("createSchema: Unknown SQL type " + meta.getColumnType(i) + " cannot be converted to Avro type");
            }
        }

        return builder.endRecord();
    }

    private static String normalizeNameForAvro(String inputName) {
        String normalizedName = inputName.replaceAll("[^A-Za-z0-9_]", "_");
        if (Character.isDigit(normalizedName.charAt(0))) {
            normalizedName = "_" + normalizedName;
        }
        return normalizedName;
    }

    /**
     * An interface for callback methods which allows processing of a row during the convertToAvroStream() processing.
     * <b>IMPORTANT:</b> This method should only work on the row pointed at by the current ResultSet reference.
     * Advancing the cursor (e.g.) can cause rows to be skipped during Avro transformation.
     */
    public interface ResultSetRowCallback {
        void processRow(ResultSet resultSet) throws IOException;
    }
}
