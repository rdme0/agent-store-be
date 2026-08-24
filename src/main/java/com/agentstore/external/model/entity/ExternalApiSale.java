package com.agentstore.external.model.entity;

import com.agentstore.common.model.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigInteger;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "external_api_sales")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExternalApiSale extends BaseEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID externalIntentId;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(nullable = false, columnDefinition = "BIGINT")
    private BigInteger providerCostAtomic;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(nullable = false, columnDefinition = "BIGINT")
    private BigInteger platformFeeAtomic;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(nullable = false, columnDefinition = "BIGINT")
    private BigInteger totalCostAtomic;

    @Column(nullable = false)
    private String payer;

    @Column(nullable = false, unique = true)
    private String transactionHash;

    public ExternalApiSale(UUID id, UUID externalIntentId, BigInteger providerCostAtomic,
            BigInteger platformFeeAtomic, BigInteger totalCostAtomic, String payer,
            String transactionHash) {
        this.id = id;
        this.externalIntentId = externalIntentId;
        this.providerCostAtomic = providerCostAtomic;
        this.platformFeeAtomic = platformFeeAtomic;
        this.totalCostAtomic = totalCostAtomic;
        this.payer = payer;
        this.transactionHash = transactionHash;
    }
}
