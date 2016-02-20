/**
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stratio.datasource.mongodb

import com.stratio.datasource.mongodb.config.MongodbConfig
import com.stratio.datasource.mongodb.partitioner.MongodbPartitioner
import com.stratio.datasource.mongodb.rdd.MongodbRDD
import com.stratio.datasource.mongodb.schema.{MongodbRowConverter, MongodbSchema}
import com.stratio.datasource.mongodb.writer.MongodbSimpleWriter
import com.stratio.datasource.util.Config
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.sources.{BaseRelation, Filter, InsertableRelation, PrunedFilteredScan}
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row, SQLContext}

/**
 * A MongoDB baseRelation that can eliminate unneeded columns
 * and filter using selected predicates before producing
 * an RDD containing all matching tuples as Row objects.
 * @param config A Mongo configuration with needed properties for MongoDB
 * @param schemaProvided The optionally provided schema. If not provided,
 *                       it will be inferred from the whole field projection
 *                       of the specified table in Spark SQL statement using
 *                       a sample ratio (as JSON Data Source does).
 * @param sqlContext An existing Spark SQL context.
 */
class MongodbRelation(private val config: Config,
                       schemaProvided: Option[StructType] = None)(
                       @transient val sqlContext: SQLContext) extends BaseRelation
with PrunedFilteredScan with InsertableRelation {

  implicit val c: Config = config

  import MongodbRelation._

  private val rddPartitioner: MongodbPartitioner =
    new MongodbPartitioner(config)

  /**
   * Default schema to be used in case no schema was provided before.
   * It scans the RDD generated by Spark SQL statement,
   * using specified sample ratio.
   */
  @transient private lazy val lazySchema =
    MongodbSchema(
      new MongodbRDD(sqlContext, config, rddPartitioner),
      config.get[Any](MongodbConfig.SamplingRatio).fold(MongodbConfig.DefaultSamplingRatio)(_.toString.toDouble)).schema()

  override val schema: StructType = schemaProvided.getOrElse(lazySchema)

  override def buildScan(
                          requiredColumns: Array[String],
                          filters: Array[Filter]): RDD[Row] = {

    val rdd = new MongodbRDD(
      sqlContext,
      config,
      rddPartitioner,
      requiredColumns,
      filters)

    MongodbRowConverter.asRow(pruneSchema(schema, requiredColumns), rdd)
  }

  /**
   * Indicates if a collection is empty.
   * @return Boolean
   */
  def isEmptyCollection: Boolean = new MongodbSimpleWriter(config).isEmpty

  /**
   * Insert data into the specified DataSource.
   * @param data Data to insert.
   * @param overwrite Boolean indicating whether to overwrite the data.
   */
  def insert(data: DataFrame, overwrite: Boolean): Unit = {
    if (overwrite) {
      new MongodbSimpleWriter(config).dropCollection
    }

    data.saveToMongodb(config)
  }

  /**
   * Compare if two MongodbRelation are the same.
   * @param other Object to compare
   * @return Boolean
   */

  override def equals(other: Any): Boolean = other match {
    case that: MongodbRelation =>
      schema == that.schema && config == that.config
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(schema, config)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }
}

object MongodbRelation {

  /**
   * Prune whole schema in order to fit with
   * required columns in Spark SQL statement.
   * @param schema Whole field projection schema.
   * @param requiredColumns Required fields in statement
   * @return A new pruned schema
   */
  def pruneSchema(
    schema: StructType,
    requiredColumns: Array[String]): StructType =
    pruneSchema(schema, requiredColumns.map(_ -> None): Array[(String, Option[Int])])


  /**
    * Prune whole schema in order to fit with
    * required columns taking in consideration nested columns (array elements) in Spark SQL statement.
    * @param schema Whole field projection schema.
    * @param requiredColumnsWithIndex Required fields in statement including index within field for random accesses.
    * @return A new pruned schema
    */
  def pruneSchema(
                  schema: StructType,
                  requiredColumnsWithIndex: Array[(String, Option[Int])]): StructType = {
		  
    val name2sfield: Map[String, StructField] = schema.fields.map(f => f.name -> f).toMap
    StructType(
      requiredColumnsWithIndex.flatMap {
        case (colname, None) => name2sfield.get(colname)
        case (colname, Some(idx)) => name2sfield.get(colname) collect {
          case field @ StructField(name, ArrayType(et,_), nullable, _) =>
            val mdataBuilder = new MetadataBuilder
            //Non-functional area
            mdataBuilder.putLong("idx", idx.toLong)
            mdataBuilder.putString("colname", name)
            //End of non-functional area
            StructField(s"$name[$idx]", et, true, mdataBuilder.build())
        }
      }
    )
  }

}