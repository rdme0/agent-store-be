DO
$$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM payment_attempts
        WHERE payment_mode = 'SIMULATED'::"PaymentMode"
    ) OR EXISTS (
        SELECT 1
        FROM revenue_entries
        WHERE payment_mode = 'SIMULATED'::"PaymentMode"
    ) THEN
        RAISE EXCEPTION 'simulated payment records require an explicit local database reset before V23';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM revenue_entries
        WHERE transaction_hash IS NULL
    ) THEN
        RAISE EXCEPTION 'revenue without a native transaction hash requires an explicit local database reset before V23';
    END IF;
END
$$;

ALTER TABLE payment_attempts
    DROP COLUMN payment_mode;

ALTER TABLE revenue_entries
    DROP COLUMN payment_mode;

ALTER TABLE revenue_entries
    ALTER COLUMN transaction_hash SET NOT NULL;

DROP TYPE "PaymentMode";
