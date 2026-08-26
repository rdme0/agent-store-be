package com.agentstore.agent.config

import com.agentstore.agent.dto.request.DemoCatalogAgentRequest
import com.agentstore.agent.dto.request.DemoCatalogDefinition
import com.agentstore.agent.dto.request.DemoCatalogDependencyRequest
import com.agentstore.agent.dto.request.DemoFunctionContractRequest
import com.agentstore.agent.model.vo.AgentResponseFormat
import com.agentstore.agent.model.vo.AgentUsageType
import com.agentstore.agent.repository.AgentRepository
import com.agentstore.agent.service.DemoCatalogRegistrationService
import com.agentstore.common.config.AgentStoreProperties
import java.util.UUID
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("dev")
class DemoCatalogInitializer(
    private val agentRepository: AgentRepository,
    private val registrationService: DemoCatalogRegistrationService,
    private val properties: AgentStoreProperties,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        if (agentRepository.count() == 0L) {
            registrationService.register(request = DemoCatalogSeed(baseUrl = properties.demoAgentBaseUrl).request())
        }
    }
}

private class DemoCatalogSeed(private val baseUrl: String) {
    companion object {
        private const val CONTRACT_VERSION = "1.0.0"
        private const val AGENT_VERSION = "1.0.0"
        private const val NETWORK = "eip155:84532"
        private const val ASSET = "0x036CbD53842c5426634e7929541eC2318f3dCF7e"
        private const val VERSION_CONSTRAINT = ">=1.0.0,<2.0.0"
        private val developerId = UUID.fromString("00000000-0000-0000-0000-00000000d001")
    }

    fun request(): DemoCatalogDefinition {
        return DemoCatalogDefinition(
            agents = listOf(
                root(
                    code = "investment-analysis",
                    name = "투자 분석",
                    description = "재무·시장 뉴스·위험 정보를 종합해 투자 판단 근거를 정리합니다.",
                    functionCode = "investment-analysis",
                    functionName = "투자 분석",
                    functionDescription = "여러 분석 결과를 종합하는 투자 분석 기능입니다.",
                    priceAtomic = "1000",
                    payToSuffix = "0101",
                    dependencies = listOf(
                        dependency(functionCode = "financial-analysis", maxPriceAtomic = "1000"),
                        dependency(functionCode = "market-news-analysis", maxPriceAtomic = "1200"),
                        dependency(functionCode = "investment-risk-analysis", maxPriceAtomic = "900"),
                    ),
                ),
                specialist(
                    code = "financial-analysis",
                    name = "재무 분석",
                    description = "기업의 재무 신호와 위험을 조사합니다.",
                    functionCode = "financial-analysis",
                    functionName = "재무 분석",
                    functionDescription = "재무 지표와 위험을 구조화해 제공하는 기능입니다.",
                    priceAtomic = "1000",
                    payToSuffix = "0102",
                ),
                specialist(
                    code = "market-news-fast",
                    name = "빠른 시장 뉴스",
                    description = "짧은 최신 시장 뉴스 분석을 제공합니다.",
                    functionCode = "market-news-analysis",
                    functionName = "시장 뉴스 분석",
                    functionDescription = "시장 뉴스 흐름과 핵심 근거를 구조화해 제공하는 기능입니다.",
                    priceAtomic = "600",
                    payToSuffix = "0103",
                ),
                specialist(
                    code = "market-news-deep",
                    name = "심층 시장 뉴스",
                    description = "상세한 최신 시장 뉴스 분석을 제공합니다.",
                    functionCode = "market-news-analysis",
                    functionName = "시장 뉴스 분석",
                    functionDescription = "시장 뉴스 흐름과 핵심 근거를 구조화해 제공하는 기능입니다.",
                    priceAtomic = "1200",
                    payToSuffix = "0104",
                ),
                specialist(
                    code = "investment-risk",
                    name = "투자 위험 분석",
                    description = "투자 판단의 주요 위험 요소를 조사합니다.",
                    functionCode = "investment-risk-analysis",
                    functionName = "투자 위험 분석",
                    functionDescription = "투자 위험 수준과 요인을 구조화해 제공하는 기능입니다.",
                    priceAtomic = "900",
                    payToSuffix = "0105",
                ),
                root(
                    code = "shopping-assistant",
                    name = "상품 구매 도우미",
                    description = "제품·리뷰·가격 정보를 비교해 구매 판단을 돕습니다.",
                    functionCode = "shopping-advice",
                    functionName = "상품 구매 조언",
                    functionDescription = "상품 후보와 가격·리뷰 근거를 종합하는 기능입니다.",
                    priceAtomic = "1000",
                    payToSuffix = "0106",
                    dependencies = listOf(
                        dependency(functionCode = "product-search", maxPriceAtomic = "800"),
                        dependency(functionCode = "review-analysis", maxPriceAtomic = "900"),
                        dependency(functionCode = "price-comparison", maxPriceAtomic = "700"),
                    ),
                ),
                specialist(
                    code = "product-search",
                    name = "상품 탐색",
                    description = "요청에 맞는 제품 후보와 특징을 찾습니다.",
                    functionCode = "product-search",
                    functionName = "상품 탐색",
                    functionDescription = "제품 후보와 핵심 특징을 구조화해 제공하는 기능입니다.",
                    priceAtomic = "800",
                    payToSuffix = "0107",
                ),
                specialist(
                    code = "review-analysis",
                    name = "리뷰 분석",
                    description = "사용자 후기에서 장점과 단점을 정리합니다.",
                    functionCode = "review-analysis",
                    functionName = "리뷰 분석",
                    functionDescription = "제품 리뷰의 장점과 단점을 구조화해 제공하는 기능입니다.",
                    priceAtomic = "900",
                    payToSuffix = "0108",
                ),
                specialist(
                    code = "price-comparison",
                    name = "가격 비교",
                    description = "판매처별 가격과 구매 조건을 비교합니다.",
                    functionCode = "price-comparison",
                    functionName = "가격 비교",
                    functionDescription = "판매처별 가격과 조건을 구조화해 제공하는 기능입니다.",
                    priceAtomic = "700",
                    payToSuffix = "0109",
                ),
                root(
                    code = "travel-planner",
                    name = "여행 계획",
                    description = "장소·날씨·안전 정보를 종합해 여행 계획을 만듭니다.",
                    functionCode = "travel-plan",
                    functionName = "여행 계획",
                    functionDescription = "여행 장소, 날씨, 안전 정보를 종합하는 기능입니다.",
                    priceAtomic = "1000",
                    payToSuffix = "0110",
                    dependencies = listOf(
                        dependency(functionCode = "destination-research", maxPriceAtomic = "800"),
                        dependency(functionCode = "weather-forecast", maxPriceAtomic = "600"),
                        dependency(functionCode = "travel-safety-analysis", maxPriceAtomic = "900"),
                    ),
                ),
                specialist(
                    code = "destination-research",
                    name = "여행지 조사",
                    description = "여행지의 볼거리와 주의점을 조사합니다.",
                    functionCode = "destination-research",
                    functionName = "여행지 조사",
                    functionDescription = "여행지의 추천 지점과 주의점을 구조화해 제공하는 기능입니다.",
                    priceAtomic = "800",
                    payToSuffix = "0111",
                ),
                specialist(
                    code = "weather-forecast",
                    name = "날씨 예보",
                    description = "여행 기간의 날씨 조건을 조사합니다.",
                    functionCode = "weather-forecast",
                    functionName = "날씨 예보",
                    functionDescription = "여행 기간별 날씨 조건을 구조화해 제공하는 기능입니다.",
                    priceAtomic = "600",
                    payToSuffix = "0112",
                ),
                specialist(
                    code = "travel-safety",
                    name = "여행 안전",
                    description = "여행지의 안전 정보와 주의 사항을 조사합니다.",
                    functionCode = "travel-safety-analysis",
                    functionName = "여행 안전 분석",
                    functionDescription = "여행 안전 수준과 행동 요령을 구조화해 제공하는 기능입니다.",
                    priceAtomic = "900",
                    payToSuffix = "0113",
                ),
            ),
        )
    }

    private fun root(
        code: String,
        name: String,
        description: String,
        functionCode: String,
        functionName: String,
        functionDescription: String,
        priceAtomic: String,
        payToSuffix: String,
        dependencies: List<DemoCatalogDependencyRequest>,
    ): DemoCatalogAgentRequest {
        return agent(
            code = code,
            name = name,
            description = description,
            functionCode = functionCode,
            functionName = functionName,
            functionDescription = functionDescription,
            responseFormat = AgentResponseFormat.MARKDOWN,
            usageType = AgentUsageType.USER_FACING,
            priceAtomic = priceAtomic,
            payToSuffix = payToSuffix,
            outputSchema = mapOf("type" to "string"),
            dependencies = dependencies,
        )
    }

    private fun specialist(
        code: String,
        name: String,
        description: String,
        functionCode: String,
        functionName: String,
        functionDescription: String,
        priceAtomic: String,
        payToSuffix: String,
    ): DemoCatalogAgentRequest {
        return agent(
            code = code,
            name = name,
            description = description,
            functionCode = functionCode,
            functionName = functionName,
            functionDescription = functionDescription,
            responseFormat = AgentResponseFormat.JSON,
            usageType = AgentUsageType.INTERNAL_COMPONENT,
            priceAtomic = priceAtomic,
            payToSuffix = payToSuffix,
            outputSchema = mapOf("type" to "object"),
            dependencies = emptyList(),
        )
    }

    private fun agent(
        code: String,
        name: String,
        description: String,
        functionCode: String,
        functionName: String,
        functionDescription: String,
        responseFormat: AgentResponseFormat,
        usageType: AgentUsageType,
        priceAtomic: String,
        payToSuffix: String,
        outputSchema: Map<String, String>,
        dependencies: List<DemoCatalogDependencyRequest>,
    ): DemoCatalogAgentRequest {
        return DemoCatalogAgentRequest(
            developerId = developerId,
            developerName = "AgentStore Demo",
            code = code,
            name = name,
            description = description,
            functionContract = DemoFunctionContractRequest(
                code = functionCode,
                contractVersion = CONTRACT_VERSION,
                name = functionName,
                description = functionDescription,
                responseFormat = responseFormat,
                inputSchema = mapOf("type" to "object", "additionalProperties" to true),
                outputSchema = outputSchema,
            ),
            semver = AGENT_VERSION,
            endpoint = "${baseUrl.trimEnd('/')}/agents/$code/invoke",
            priceAtomic = priceAtomic,
            network = NETWORK,
            asset = ASSET,
            payTo = "0x000000000000000000000000000000000000$payToSuffix",
            responseFormat = responseFormat,
            usageType = usageType,
            dependencies = dependencies,
        )
    }

    private fun dependency(functionCode: String, maxPriceAtomic: String): DemoCatalogDependencyRequest {
        return DemoCatalogDependencyRequest(
            functionCode = functionCode,
            contractVersion = CONTRACT_VERSION,
            versionConstraint = VERSION_CONSTRAINT,
            maxPriceAtomic = maxPriceAtomic,
        )
    }
}
