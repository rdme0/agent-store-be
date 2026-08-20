-- V11 is already immutable once applied. Mark only historical settlements with both
-- durable revenue and a post-payment step state as locally projected; ambiguous rows
-- deliberately remain unprojected and keep their reservation for safe recovery.
UPDATE payment_attempts attempt
SET projected_at = CURRENT_TIMESTAMP
FROM revenue_entries revenue,
     execution_steps step
WHERE revenue.payment_attempt_id = attempt.id
  AND step.id = attempt.execution_step_id
  AND attempt.status = 'SETTLED'::"PaymentAttemptStatus"
  AND step.status IN
      ('PAYMENT_SETTLED'::"ExecutionStepStatus", 'RUNNING'::"ExecutionStepStatus", 'COMPLETED'::"ExecutionStepStatus")
  AND attempt.projected_at IS NULL;
