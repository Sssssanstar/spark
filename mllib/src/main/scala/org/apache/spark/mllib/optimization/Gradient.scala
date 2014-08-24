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

package org.apache.spark.mllib.optimization

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.mllib.linalg.BLAS.{axpy, dot, scal}
import scala.math.pow

/**
 * :: DeveloperApi ::
 * Class used to compute the gradient for a loss function, given a single data point.
 */
@DeveloperApi
abstract class Gradient extends Serializable {
  /**
   * Compute the gradient and loss given the features of a single data point.
   *
   * @param data features for one data point
   * @param label label for this data point
   * @param weights weights/coefficients corresponding to features
   *
   * @return (gradient: Vector, loss: Double)
   */
  def compute(data: Vector, label: Double, weights: Vector): (Vector, Double)

  /**
   * Compute the gradient and loss given the features of a single data point,
   * add the gradient to a provided vector to avoid creating new objects, and return loss.
   *
   * @param data features for one data point
   * @param label label for this data point
   * @param weights weights/coefficients corresponding to features
   * @param cumGradient the computed gradient will be added to this vector
   *
   * @return loss
   */
  def compute(data: Vector, label: Double, weights: Vector, cumGradient: Vector): Double
}

/**
 * :: DeveloperApi ::
 * Compute gradient and loss for a logistic loss function, as used in binary classification.
 * See also the documentation for the precise formulation.
 */
@DeveloperApi
class LogisticGradient extends Gradient {
  override def compute(data: Vector, label: Double, weights: Vector): (Vector, Double) = {
    val margin = -1.0 * dot(data, weights)
    val gradientMultiplier = (1.0 / (1.0 + math.exp(margin))) - label
    val gradient = data.copy
    scal(gradientMultiplier, gradient)
    val loss =
      if (label > 0) {
        math.log1p(math.exp(margin)) // log1p is log(1+p) but more accurate for small p
      } else {
        math.log1p(math.exp(margin)) - margin
      }

    (gradient, loss)
  }

  override def compute(
      data: Vector,
      label: Double,
      weights: Vector,
      cumGradient: Vector): Double = {
    val margin = -1.0 * dot(data, weights)
    val gradientMultiplier = (1.0 / (1.0 + math.exp(margin))) - label
    axpy(gradientMultiplier, data, cumGradient)
    if (label > 0) {
      math.log1p(math.exp(margin))
    } else {
      math.log1p(math.exp(margin)) - margin
    }
  }
}

/**
 * :: DeveloperApi ::
 * Compute gradient and loss for a Least-squared loss function, as used in linear regression.
 * This is correct for the averaged least squares loss function (mean squared error)
 *              L = 1/n ||A weights-y||^2
 * See also the documentation for the precise formulation.
 */
@DeveloperApi
class LeastSquaresGradient extends Gradient {
  override def compute(data: Vector, label: Double, weights: Vector): (Vector, Double) = {
    val diff = dot(data, weights) - label
    val loss = diff * diff
    val gradient = data.copy
    scal(2.0 * diff, gradient)
    (gradient, loss)
  }

  override def compute(
      data: Vector,
      label: Double,
      weights: Vector,
      cumGradient: Vector): Double = {
    val diff = dot(data, weights) - label
    axpy(2.0 * diff, data, cumGradient)
    diff * diff
  }
}

/**
 * :: DeveloperApi ::
 * Compute gradient and loss for Huber objective function, as used in robust regression.
 * The Huber M-estimator corresponds to a probability distribution for the errors which is normal
 * in the centre but like a double exponential distribution in the tails (Hogg 1979: 109).
 *              L = 1/2 ||A weights-y||^2       if |A weights-y| <= k
 *              L = k |A weights-y| - 1/2 K^2   if |A weights-y| > k
 * where k = 1.345 which produce 95% efficiency when the errors are normal and
 * substantial resistance to outliers otherwise.
 * See also the documentation for the precise formulation.
 */
@DeveloperApi
class HuberRobustGradient extends Gradient {
  override def compute(data: Vector, label: Double, weights: Vector): (Vector, Double) = {
    val diff = dot(data, weights) - label
    val loss = diff * diff
    val gradient = data.copy
    val k = 1.345
    /**
    * Tuning constant is generally picked to give reasonably high efficiency in the normal case.
    * Smaller values produce more resistance to outliers while at the expense of
    * lower efficiency when the errors are normally distributed.
    */
    if(diff < -k){
      scal(-k, gradient)
      (gradient, (-k * diff - 1.0 / 2.0 * pow(k, 2)))
    }else if(diff >= -k && diff <= k){
      scal(diff, gradient)
      (gradient, (1.0 / 2.0 * loss))
    }else {
      scal(k, gradient)
      (gradient, (k * diff - 1.0 / 2.0 * pow(k, 2)))
    }
  }

  override def compute(
                        data: Vector,
                        label: Double,
                        weights: Vector,
                        cumGradient: Vector): Double = {
    val diff = dot(data, weights) - label
    val k = 1.345
    if(diff < -k){
      axpy(-k, data, cumGradient)
    }else if(diff >= -k && diff <= k){
      axpy(diff, data, cumGradient)
    }else {
      axpy(k, data, cumGradient)
    }
    if(diff < -k){
      -k * diff - 1.0 / 2.0 * pow(k, 2)
    }else if(diff >= -k && diff <= k){
      1.0 / 2.0 * loss
    }else {
      k * diff - 1.0 /2.0 * pow(k, 2)
    }
  }
}

/**
 * :: DeveloperApi ::
 * Compute gradient and loss for Turkey bisquare weight function, as used in robust regression.
 * The biweight function produces and M-estimator that is more resistant to regression
 * outliers than the Huber M-estimator (Andersen 2008: 19). Especially on the extreme tails.
 *              L = k^2 / 6 * (1 - (1 - ||A weights-y||^2 / k^2)^3)     if |A weights-y| <= k
 *              L = k^2 / 6                                             if |A weights-y| > k
 * where k = 4.685 which produce 95% efficiency when the errors are normal and
 * substantial resistance to outliers otherwise.
 * See also the documentation for the precise formulation.
 */
@DeveloperApi
class BiweightRobustGradient extends Gradient {
  override def compute(data: Vector, label: Double, weights: Vector): (Vector, Double) = {
    val diff = dot(data, weights) - label
    val loss = diff * diff
    val gradient = data.copy
    val k = 4.685
    /**
     * Tuning constant is generally picked to give reasonably high efficiency in the normal case.
     * Smaller values produce more resistance to outliers while at the expense of
     * lower efficiency when the errors are normally distributed.
     */
    if(diff >= -k && diff <= k){
      scal(pow((1 - loss / pow(k, 2)), 2) * diff, gradient)
      (gradient, (pow(k, 2) / 6.0 * (1 - pow((1 - loss / pow(k, 2)), 3))))
    }else {
      scal(0.0, gradient)
      (gradient, pow(k, 2) / 6.0)
    }
  }

  override def compute(
                        data: Vector,
                        label: Double,
                        weights: Vector,
                        cumGradient: Vector): Double = {
    val diff = dot(data, weights) - label
    val loss = diff * diff
    val k = 4.685
    if(diff >= -k && diff <= k){
      axpy(pow((1 - loss / pow(k, 2)), 2) * diff, data, cumGradient)
    }else {
      axpy(0.0, data, cumGradient)
    }
    if(diff >= -k && diff <= k){
      pow(k, 2) / 6.0 * (1 - pow((1 - loss / pow(k, 2)), 3))
    }else {
      pow(k, 2) / 6.0
    }
  }
}

/**
 * :: DeveloperApi ::
 * Compute gradient and loss for a Hinge loss function, as used in SVM binary classification.
 * See also the documentation for the precise formulation.
 * NOTE: This assumes that the labels are {0,1}
 */
@DeveloperApi
class HingeGradient extends Gradient {
  override def compute(data: Vector, label: Double, weights: Vector): (Vector, Double) = {
    val dotProduct = dot(data, weights)
    // Our loss function with {0, 1} labels is max(0, 1 - (2y – 1) (f_w(x)))
    // Therefore the gradient is -(2y - 1)*x
    val labelScaled = 2 * label - 1.0
    if (1.0 > labelScaled * dotProduct) {
      val gradient = data.copy
      scal(-labelScaled, gradient)
      (gradient, 1.0 - labelScaled * dotProduct)
    } else {
      (Vectors.sparse(weights.size, Array.empty, Array.empty), 0.0)
    }
  }

  override def compute(
      data: Vector,
      label: Double,
      weights: Vector,
      cumGradient: Vector): Double = {
    val dotProduct = dot(data, weights)
    // Our loss function with {0, 1} labels is max(0, 1 - (2y – 1) (f_w(x)))
    // Therefore the gradient is -(2y - 1)*x
    val labelScaled = 2 * label - 1.0
    if (1.0 > labelScaled * dotProduct) {
      axpy(-labelScaled, data, cumGradient)
      1.0 - labelScaled * dotProduct
    } else {
      0.0
    }
  }
}
