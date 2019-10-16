package com.swoop.alchemy.spark.expressions.hll.agkn

import java.sql.{DriverManager, ResultSet}

import com.swoop.alchemy.spark.expressions.hll.IMPLEMENTATION_CONFIG_KEY
import com.swoop.alchemy.spark.expressions.hll.functions._
import com.swoop.spark.test.HiveSqlSpec
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.scalatest.{Matchers, WordSpec}

case class Postgres(user: String, database: String, port: Int) {
  val con_str = s"jdbc:postgresql://localhost:${port}/${database}?user=${user}"

  def execute[T](query: String, handler: ResultSet => T): T = {
    val conn = DriverManager.getConnection(con_str)
    try {
      val stm = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
      return handler(stm.executeQuery(query))
    } finally {
      conn.close()
    }
  }

  def update(query: String): Unit = {
    val conn = DriverManager.getConnection(con_str)
    try {
      val stm = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
      stm.executeUpdate(query)
    } finally {
      conn.close()
    }
  }

  def sparkRead(schema: String, table: String)(implicit spark: SparkSession): DataFrame =
    spark.read
      .format("jdbc")
      .option("url", s"jdbc:postgresql:${database}")
      .option("dbtable", s"${schema}.${table}")
      .option("user", user)
      .load()

  def sparkWrite(schema: String, table: String)(df: DataFrame) =
    df.write
      .format("jdbc")
      .option("url", s"jdbc:postgresql:${database}")
      .option("dbtable", s"${schema}.${table}")
      .option("user", user)
      .save()
}

class PostgresInteropTest extends WordSpec with Matchers with HiveSqlSpec {
  lazy val spark = sqlc.sparkSession
  lazy val pg = Postgres("postgres", "postgres", 5432)

  "Postgres interop" should {
    "calculate same results" in {
      import spark.implicits._

      // use Aggregate Knowledge (Postgres-compatible) HLL implementation
      spark.conf.set(IMPLEMENTATION_CONFIG_KEY, "AGKN")

      // init Postgres extension for database
      pg.update("CREATE EXTENSION IF NOT EXISTS hll;")


      // create some random not-entirely distinct rows
      val rand = new scala.util.Random(42)
      val n = 100000
      val randomDF = sc.parallelize(
        Seq.fill(n) {
          (rand.nextInt(24), rand.nextInt(n))
        }
      ).toDF("hour", "id").cache

      // create hll aggregates (by hour)
      val byHourDF = randomDF.groupBy("hour").agg(hll_init_agg("id", .39).as("hll_id")).cache

      // send hlls to postgres
      pg.update("DROP TABLE IF EXISTS spark_hlls CASCADE;")
      pg.sparkWrite("public", "spark_hlls")(byHourDF)

      // convert hll column from `bytea` to `hll` type
      pg.update(
        """
          |ALTER TABLE spark_hlls
          |ALTER COLUMN hll_id TYPE hll USING CAST (hll_id AS hll);
          |""".stripMargin
      )

      // re-aggregate all hours in Spark
      val distinctSpark = byHourDF.select(hll_cardinality(hll_merge(byHourDF("hll_id")))).as[Long].first()
      // re-aggregate all hours in Postgres
      val distinctPostgres = pg.execute(
        "SELECT CAST (hll_cardinality(hll_union_agg(hll_id)) as Integer) AS approx FROM spark_hlls",
        (rs) => {
          rs.next;
          rs.getInt("approx")
        }
      )

      distinctSpark should be(distinctPostgres)
    }
  }
}
