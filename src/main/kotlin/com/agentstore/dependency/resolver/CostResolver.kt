package com.agentstore.dependency.resolver

import com.agentstore.common.web.ApiException
import com.agentstore.dependency.model.vo.CostCalculation
import com.agentstore.dependency.model.vo.ResolvedNode
import org.springframework.stereotype.Service
import java.math.BigInteger

@Service
class CostResolver {
    fun resolve(root: ResolvedNode): CostCalculation = calculate(root, 0)

    private fun calculate(node: ResolvedNode, depth: Int): CostCalculation {
        var cost = node.version.priceAtomic
        var steps = 1
        var maxDepth = depth
        node.dependencies.forEach { edge ->
            edge.resolved?.let { child ->
                val calculation = calculate(child, depth + 1)
                cost += edge.dependency.maxCalls.toBigInteger() * calculation.maxCostAtomic
                steps += edge.dependency.maxCalls * calculation.steps
                maxDepth = maxOf(maxDepth, calculation.maxDepth)
                if (steps > 32) throw ApiException("EXECUTION_STEPS_EXCEEDED", "Dependency graph exceeds the maximum execution steps", 422, mapOf("maxSteps" to 32, "steps" to steps))
            }
        }
        if (cost > BigInteger.TEN.pow(60)) throw ApiException("COST_OVERFLOW", "Maximum execution cost is too large", 422)
        return CostCalculation(cost, steps, maxDepth)
    }
}
