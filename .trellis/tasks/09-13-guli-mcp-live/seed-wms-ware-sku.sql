-- 库存工具验证用测试数据（对应 design.md 变更 4 / prd.md D2）
-- 目的：wms_ware_sku 原表 0 行，导致 product_stock_query 只能走空分支，
--       无法验证解析修正是否真的生效。此处补 4 行真实存在的 sku_id，覆盖各分支。
-- 仅插数据，不改 guli2 源码，不改表结构。
-- 回滚：DELETE FROM wms_ware_sku WHERE sku_id IN (1,2,3);

USE `ry-cloud`;

-- 幂等：先清掉本 seed 涉及的行，避免重复执行产生脏数据
DELETE FROM `wms_ware_sku` WHERE sku_id IN (1,2,3);

-- 覆盖意图：
--   sku 1 / ware 1 + ware 2 → 同一 SKU 跨两仓，验证多仓汇总与"充足"分支
--                             （总 160，锁定 15，可用 145 > 30）
--   sku 2 / ware 1          → 可用 8，命中"紧张"分支（<=10）
--   sku 3 / ware 1          → 可用 23，命中"较少"分支（10-30）
INSERT INTO `wms_ware_sku` (sku_id, ware_id, stock, sku_name, stock_locked) VALUES
  (1, 1, 120, '华为 HUAWEI Mate 30 Pro 星河银 8GB+256GB', 5),
  (1, 2,  40, '华为 HUAWEI Mate 30 Pro 星河银 8GB+256GB', 10),
  (2, 1,   8, '华为 HUAWEI Mate 30 Pro 星河银 8GB+128GB', 0),
  (3, 1,  25, '华为 HUAWEI Mate 30 Pro 亮黑色 8GB+256GB', 2);
