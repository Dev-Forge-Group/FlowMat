-- V114: Who should count an open count plan (docs/domain/stock-count.md P9-P10). Blind baselines stay with the owner.
ALTER TABLE inventory_count_plan ADD COLUMN assigned_to varchar(50);
