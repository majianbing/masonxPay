-- Phase 3.5: Cost-aware routing — optional cost ceiling per routing rule
--
-- max_cost_bps: maximum acceptable effective cost expressed in basis points of the transaction amount
--              (e.g. 300 = 3.00% of transaction value).
--              NULL = no cost ceiling enforced for this rule (existing behaviour preserved).
--
-- At routing time, any connector whose effectiveCost > (amount * max_cost_bps / 10000)
-- is excluded from the candidate pool for this rule.

ALTER TABLE routing_rules
    ADD COLUMN max_cost_bps INT;
