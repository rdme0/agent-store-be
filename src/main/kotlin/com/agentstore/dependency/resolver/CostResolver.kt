package com.agentstore.dependency.resolver

import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.dependency.model.vo.CostCalculation
import com.agentstore.dependency.model.vo.ResolvedNode
import java.math.BigInteger
import org.springframework.stereotype.Component

@Component
class CostResolver {
    fun resolve(root: ResolvedNode): CostCalculation {
        return calculate(node = root, depth = 0)
    }

    private fun calculate(node: ResolvedNode, depth: Int): CostCalculation {
        var cost = node.version.priceAtomic
        var steps = 1
        var maxDepth = depth
        node.dependencies.forEach { edge ->
            edge.resolved?.let { child ->
                val calculation = calculate(node = child, depth = depth + 1)
                cost += edge.dependency.maxCalls.toBigInteger() * calculation.maxCostAtomic
                steps += edge.dependency.maxCalls * calculation.steps
                maxDepth = maxOf(a = maxDepth, b = calculation.maxDepth)
                if (steps > 32) {
                    throw DomainClientException(ErrorCode.EXECUTION_STEPS_EXCEEDED)
                }
            }
        }
        if (cost > BigInteger.TEN.pow(60)) {
            throw DomainClientException(ErrorCode.COST_OVERFLOW)
        }
        return CostCalculation(
            maxCostAtomic = cost,
            steps = steps,
            maxDepth = maxDepth,
        )
    }
}
