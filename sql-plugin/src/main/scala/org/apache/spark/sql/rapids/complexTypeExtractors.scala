/*
 * Copyright (c) 2020, NVIDIA CORPORATION.
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

package org.apache.spark.sql.rapids

import ai.rapids.cudf.{ColumnVector, Scalar}
import com.nvidia.spark.rapids.{BinaryExprMeta, ConfKeysAndIncompat, GpuBinaryExpression, GpuColumnVector, GpuExpression, GpuOverrides, RapidsConf, RapidsMeta}

import org.apache.spark.sql.catalyst.analysis.TypeCheckResult
import org.apache.spark.sql.catalyst.expressions.{ExpectsInputTypes, Expression, ExtractValue, GetArrayItem, GetMapValue, ImplicitCastInputTypes, NullIntolerant}
import org.apache.spark.sql.catalyst.util.TypeUtils
import org.apache.spark.sql.types.{AbstractDataType, AnyDataType, ArrayType, DataType, IntegralType, MapType, StringType, StructType}

class GpuGetArrayItemMeta(
    expr: GetArrayItem,
    conf: RapidsConf,
    parent: Option[RapidsMeta[_, _, _]],
    rule: ConfKeysAndIncompat)
    extends BinaryExprMeta[GetArrayItem](expr, conf, parent, rule) {
  import GpuOverrides._

  override def tagExprForGpu(): Unit = {
    val litOrd = extractLit(expr.ordinal)
    if (litOrd.isEmpty) {
      willNotWorkOnGpu("only literal ordinals are supported")
    } else {
      // Once literal array/struct types are supported this can go away
      val ord = litOrd.get.value
      if (ord == null || ord.asInstanceOf[Int] < 0) {
        expr.dataType match {
          case ArrayType(_, _) | MapType(_, _, _) | StructType(_) =>
            willNotWorkOnGpu("negative and null indexes are not supported for nested types")
          case _ =>
        }
      }
    }
  }
  override def convertToGpu(
      arr: Expression,
      ordinal: Expression): GpuExpression =
    GpuGetArrayItem(arr, ordinal)

  override def isSupportedType(t: DataType): Boolean =
    GpuOverrides.isSupportedType(t, allowArray = true, allowNesting = true)
}

/**
 * Returns the field at `ordinal` in the Array `child`.
 *
 * We need to do type checking here as `ordinal` expression maybe unresolved.
 */
case class GpuGetArrayItem(child: Expression, ordinal: Expression)
    extends GpuBinaryExpression with ExpectsInputTypes with ExtractValue {

  // We have done type checking for child in `ExtractValue`, so only need to check the `ordinal`.
  override def inputTypes: Seq[AbstractDataType] = Seq(AnyDataType, IntegralType)

  override def toString: String = s"$child[$ordinal]"
  override def sql: String = s"${child.sql}[${ordinal.sql}]"

  override def left: Expression = child
  override def right: Expression = ordinal
  // Eventually we need something more full featured like
  // GetArrayItemUtil.computeNullabilityFromArray
  override def nullable: Boolean = true
  override def dataType: DataType = child.dataType.asInstanceOf[ArrayType].elementType

  override def doColumnar(lhs: GpuColumnVector, rhs: GpuColumnVector): ColumnVector =
    throw new IllegalStateException("This is not supported yet")

  override def doColumnar(lhs: Scalar, rhs: GpuColumnVector): ColumnVector =
    throw new IllegalStateException("This is not supported yet")

  override def doColumnar(lhs: GpuColumnVector, ordinal: Scalar): ColumnVector = {
    // Need to handle negative indexes...
    if (ordinal.isValid && ordinal.getInt >= 0) {
      lhs.getBase.extractListElement(ordinal.getInt)
    } else {
      withResource(Scalar.fromNull(GpuColumnVector.getRapidsType(dataType))) { nullScalar =>
        ColumnVector.fromScalar(nullScalar, lhs.getRowCount.toInt)
      }
    }
  }

  override def doColumnar(numRows: Int, lhs: Scalar, rhs: Scalar): ColumnVector = {
    withResource(GpuColumnVector.from(lhs, numRows, left.dataType)) { expandedLhs =>
      doColumnar(expandedLhs, rhs)
    }
  }
}

class GpuGetMapValueMeta(
  expr: GetMapValue,
  conf: RapidsConf,
  parent: Option[RapidsMeta[_, _, _]],
  rule: ConfKeysAndIncompat)
  extends BinaryExprMeta[GetMapValue](expr, conf, parent, rule) {
  import GpuOverrides._

  override def tagExprForGpu(): Unit = {
    if (!isStringLit(expr.key)) {
      willNotWorkOnGpu("Only String literal keys are supported")
    }
  }

  override def convertToGpu(
    child: Expression,
    key: Expression): GpuExpression =
    GpuGetMapValue(child, key)

  override def isSupportedType(t: DataType): Boolean =
    GpuOverrides.isSupportedType(t, allowStringMaps = true)
}

case class GpuGetMapValue(child: Expression, key: Expression)
  extends GpuBinaryExpression with ImplicitCastInputTypes with NullIntolerant {

  private def keyType = child.dataType.asInstanceOf[MapType].keyType

  override def checkInputDataTypes(): TypeCheckResult = {
    super.checkInputDataTypes() match {
      case f: TypeCheckResult.TypeCheckFailure => f
      case TypeCheckResult.TypeCheckSuccess =>
        TypeUtils.checkForOrderingExpr(keyType, s"function $prettyName")
    }
  }

  override def dataType: DataType = child.dataType.asInstanceOf[MapType].valueType

  override def inputTypes: Seq[AbstractDataType] = Seq(AnyDataType, keyType)

  override def prettyName: String = "getMapValue"

  override def doColumnar(lhs: GpuColumnVector, rhs: Scalar): ColumnVector =
    lhs.getBase.getMapValue(rhs)

  override def doColumnar(numRows: Int, lhs: Scalar, rhs: Scalar): ColumnVector = {
    withResource(GpuColumnVector.from(lhs, numRows, left.dataType)) { expandedLhs =>
      doColumnar(expandedLhs, rhs)
    }
  }

  override def doColumnar(lhs: Scalar, rhs: GpuColumnVector): ColumnVector =
    throw new IllegalStateException("This is not supported yet")

  override def doColumnar(lhs: GpuColumnVector, rhs: GpuColumnVector): ColumnVector =
    throw new IllegalStateException("This is not supported yet")

  override def left: Expression = child

  override def right: Expression = key
}