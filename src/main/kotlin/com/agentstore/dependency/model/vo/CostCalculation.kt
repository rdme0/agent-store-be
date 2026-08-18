package com.agentstore.dependency.model.vo

import java.math.BigInteger

data class CostCalculation(val maxCostAtomic: BigInteger, val steps: Int, val maxDepth: Int)
