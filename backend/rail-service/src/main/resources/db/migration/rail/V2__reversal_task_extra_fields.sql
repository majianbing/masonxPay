-- MR2: add reversal context so the task is self-contained for 0400 construction.
-- original_stan / original_rrn / original_tx_time come from the timed-out 0100.
-- network identifies which adapter sends the 0400 (VISA_SIM, MC_SIM).

ALTER TABLE rail_reversal_task
    ADD COLUMN network           VARCHAR(30),
    ADD COLUMN original_stan     VARCHAR(6),
    ADD COLUMN original_rrn      VARCHAR(12),
    ADD COLUMN original_tx_time  TIMESTAMPTZ;
