/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql
package execution

import org.apache.spark.rdd.RDD

import org.apache.spark.sql.catalyst.analysis.MultiInstanceRelation
import org.apache.spark.sql.catalyst.plans.QueryPlan
import org.apache.spark.sql.catalyst.plans.logical
import org.apache.spark.sql.catalyst.plans.physical._
import org.apache.spark.sql.catalyst.trees

abstract class SparkPlan extends QueryPlan[SparkPlan] with Logging {
  self: Product =>

  // TODO: Move to `DistributedPlan`
  /** Specifies how data is partitioned across different nodes in the cluster. */
  def outputPartitioning: Partitioning = UnknownPartitioning(0) // TODO: WRONG WIDTH!
  /** Specifies any partition requirements on the input data for this operator. */
  def requiredChildDistribution: Seq[Distribution] =
    Seq.fill(children.size)(UnspecifiedDistribution)

  /**
   * Runs this query returning the result as an RDD.
   */
  def execute(): RDD[Row]

  /**
   * Runs this query returning the result as an array.
   */
  def executeCollect(): Array[Row] = execute().collect()

  protected def buildRow(values: Seq[Any]): Row =
    new catalyst.expressions.GenericRow(values.toArray)
}

/**
 * Allows already planned SparkQueries to be linked into logical query plans.
 *
 * Note that in general it is not valid to use this class to link multiple copies of the same
 * physical operator into the same query plan as this violates the uniqueness of expression ids.
 * Special handling exists for ExistingRdd as these are already leaf operators and thus we can just
 * replace the output attributes with new copies of themselves without breaking any attribute
 * linking.
 */
case class SparkLogicalPlan(alreadyPlanned: SparkPlan)
  extends logical.LogicalPlan with MultiInstanceRelation {

  def output = alreadyPlanned.output
  def references = Set.empty
  def children = Nil

  override final def newInstance: this.type = {
    SparkLogicalPlan(
      alreadyPlanned match {
        case ExistingRdd(output, rdd) => ExistingRdd(output.map(_.newInstance), rdd)
        case _ => sys.error("Multiple instance of the same relation detected.")
      }).asInstanceOf[this.type]
  }
}

trait LeafNode extends SparkPlan with trees.LeafNode[SparkPlan] {
  self: Product =>
}

trait UnaryNode extends SparkPlan with trees.UnaryNode[SparkPlan] {
  self: Product =>
  override def outputPartitioning: Partitioning = child.outputPartitioning
}

trait BinaryNode extends SparkPlan with trees.BinaryNode[SparkPlan] {
  self: Product =>
}
