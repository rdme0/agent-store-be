package com.agentstore.payment.model.entity;

import com.agentstore.common.model.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.UUID;

@Entity
@Table(name = "payment_settlement_journals", uniqueConstraints = @UniqueConstraint(columnNames = "payment_attempt_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentSettlementJournal extends BaseEntity {
    @Id
    private UUID id;

    @Column(name = "payment_attempt_id", nullable = false, unique = true)
    private UUID paymentAttemptId;

    @Column(name = "transaction_hash", nullable = false)
    private String transactionHash;

}
