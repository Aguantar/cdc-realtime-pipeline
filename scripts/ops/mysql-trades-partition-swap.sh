#!/usr/bin/env bash
# A-1 런북: crypto_trades → 체결 시각 일 파티션 테이블로 무정지 교체 (docs/28 A-2·A-3·A-3-1). 단계별 실행: prepare | copy | swap | finalize | rollback
set -euo pipefail
cd "$(dirname "$0")/../.."
P=$(grep '^MYSQL_ROOT_PASSWORD=' .env | cut -d= -f2-)
MY(){ docker exec -i cdc-mysql mysql -uroot -p"$P" --default-character-set=utf8mb4 -e "$1" 2>&1 | grep -v "Warning: Using" || true; }
MYN(){ docker exec cdc-mysql mysql -uroot -p"$P" -N -e "$1" 2>/dev/null; }
CH(){ docker exec cdc-clickhouse clickhouse-client -q "$1"; }
say(){ echo "[$(date -u +%FT%TZ)] $*"; }
LOG=/home/calme/kafka-reassign/a1-swap-$(date -u +%Y%m%d).log; mkdir -p /home/calme/kafka-reassign
PHASE=${1:-status}
parts(){ python3 -c "
import datetime as dt
out=[]
for i in range(-8, 3):   # 오늘 −8일 ~ +2일
    d=dt.date.today()+dt.timedelta(days=i); n=int(dt.datetime(d.year,d.month,d.day,tzinfo=dt.timezone.utc).timestamp()*1000)//86400000
    out.append(f\"PARTITION p{d.strftime('%Y%m%d')} VALUES LESS THAN ({n+1})\")
print(', '.join(out))"; }

case "$PHASE" in
prepare)
  say "prepare: 새 테이블 생성 + 유지보수 프로시저" | tee -a $LOG
  MY "CREATE TABLE crypto_db.crypto_trades_p (
    trade_id BIGINT NOT NULL AUTO_INCREMENT,
    market VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trade_price DECIMAL(20,8) NOT NULL, trade_volume DECIMAL(20,8) NOT NULL, trade_amount DECIMAL(20,4) NOT NULL,
    ask_bid CHAR(3) CHARACTER SET ascii NOT NULL,
    upbit_timestamp BIGINT NOT NULL, sequential_id BIGINT NOT NULL,
    recv_ms BIGINT NULL COMMENT 'producer WS 수신 epoch ms',
    created_at TIMESTAMP(3) NULL DEFAULT CURRENT_TIMESTAMP(3),
    best_ask_price DECIMAL(20,8) NULL, best_ask_size DECIMAL(20,8) NULL, best_bid_price DECIMAL(20,8) NULL, best_bid_size DECIMAL(20,8) NULL,
    ingest_source ENUM('ws','gapfill','backfill') NOT NULL DEFAULT 'ws',
    stream_type ENUM('REALTIME','SNAPSHOT') NOT NULL DEFAULT 'REALTIME',
    PRIMARY KEY (trade_id, upbit_timestamp),
    UNIQUE KEY uk_market_seq (market, sequential_id, upbit_timestamp),
    KEY idx_market_ts (market, upbit_timestamp)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='체결 원장 v2 (체결 시각 일 파티션, docs/28)'
  PARTITION BY RANGE (upbit_timestamp DIV 86400000) ($(parts), PARTITION p_max VALUES LESS THAN MAXVALUE)"
  MY "DROP PROCEDURE IF EXISTS crypto_db.manage_trade_partitions;
  CREATE PROCEDURE crypto_db.manage_trade_partitions()
  BEGIN
    DECLARE tomorrow_n BIGINT; DECLARE nm VARCHAR(16); DECLARE cutoff_n BIGINT; DECLARE done INT DEFAULT 0; DECLARE pn VARCHAR(64); DECLARE pd BIGINT;
    DECLARE cur CURSOR FOR SELECT partition_name, CAST(partition_description AS UNSIGNED) FROM information_schema.partitions WHERE table_schema='crypto_db' AND table_name='crypto_trades' AND partition_name <> 'p_max' AND partition_description <> 'MAXVALUE';
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;
    SET tomorrow_n = FLOOR(UNIX_TIMESTAMP(UTC_DATE()) / 86400) + 1;   -- 내일의 일 번호
    SET nm = CONCAT('p', DATE_FORMAT(UTC_DATE() + INTERVAL 1 DAY, '%Y%m%d'));
    IF NOT EXISTS (SELECT 1 FROM information_schema.partitions WHERE table_schema='crypto_db' AND table_name='crypto_trades' AND partition_name = nm) THEN
      SET @sql = CONCAT('ALTER TABLE crypto_db.crypto_trades REORGANIZE PARTITION p_max INTO (PARTITION ', nm, ' VALUES LESS THAN (', tomorrow_n + 1, '), PARTITION p_max VALUES LESS THAN MAXVALUE)');
      PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
    END IF;
    -- p_max 자가 치유: 미래·미생성 날짜의 행이 p_max 에 있으면 그 날짜 파티션을 만들어 옮긴다 (알림 대신 구조로 해결)
    SET done = 0;
    WHILE (SELECT count(*) FROM crypto_db.crypto_trades PARTITION (p_max)) > 0 AND done < 10 DO
      SET @dn = (SELECT min(upbit_timestamp DIV 86400000) FROM crypto_db.crypto_trades PARTITION (p_max));
      SET @nm2 = CONCAT('p', DATE_FORMAT(FROM_UNIXTIME(@dn * 86400), '%Y%m%d'));
      SET @sql = CONCAT('ALTER TABLE crypto_db.crypto_trades REORGANIZE PARTITION p_max INTO (PARTITION ', @nm2, ' VALUES LESS THAN (', @dn + 1, '), PARTITION p_max VALUES LESS THAN MAXVALUE)');
      PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
      SET done = done + 1;
    END WHILE;
    SET done = 0;
    SET cutoff_n = FLOOR(UNIX_TIMESTAMP(UTC_DATE()) / 86400) - 7;    -- 7일 지난 파티션(상한 ≤ cutoff) DROP
    OPEN cur;
    read_loop: LOOP
      FETCH cur INTO pn, pd; IF done = 1 THEN LEAVE read_loop; END IF;
      IF pd <= cutoff_n THEN SET @sql = CONCAT('ALTER TABLE crypto_db.crypto_trades DROP PARTITION ', pn); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s; END IF;
    END LOOP;
    CLOSE cur;
  END"
  MYN "SELECT partition_name, partition_description FROM information_schema.partitions WHERE table_schema='crypto_db' AND table_name='crypto_trades_p' ORDER BY partition_ordinal_position" | tr '\t' ':' | tr '\n' ' ' | tee -a $LOG; echo
  ;;
copy)
  say "copy: 옛 테이블 → 새 테이블, 일 단위, sql_log_bin=0" | tee -a $LOG
  for d in $(MYN "SELECT DISTINCT FROM_UNIXTIME(upbit_timestamp DIV 1000, '%Y-%m-%d') FROM crypto_db.crypto_trades ORDER BY 1"); do
    s=$(date +%s); LO=$(date -u -d "$d" +%s)000; HI=$(date -u -d "$d +1 day" +%s)000
    MY "SET sql_log_bin=0; INSERT IGNORE INTO crypto_db.crypto_trades_p (trade_id, market, trade_price, trade_volume, trade_amount, ask_bid, upbit_timestamp, sequential_id, created_at, best_ask_price, best_ask_size, best_bid_price, best_bid_size) SELECT trade_id, market, trade_price, trade_volume, trade_amount, ask_bid, upbit_timestamp, sequential_id, created_at, best_ask_price, best_ask_size, best_bid_price, best_bid_size FROM crypto_db.crypto_trades WHERE upbit_timestamp >= $LO AND upbit_timestamp < $HI"
    say "  $d: src $(MYN "SELECT count(*) FROM crypto_db.crypto_trades WHERE upbit_timestamp >= $LO AND upbit_timestamp < $HI") dst $(MYN "SELECT count(*) FROM crypto_db.crypto_trades_p WHERE upbit_timestamp >= $LO AND upbit_timestamp < $HI") ($(( $(date +%s) - s ))s)" | tee -a $LOG
  done
  say "  p_max rows: $(MYN "SELECT count(*) FROM crypto_db.crypto_trades_p PARTITION (p_max)"), ch 60s: $(CH "SELECT count() FROM cdc_pipeline.crypto_trades WHERE flink_ts >= now() - INTERVAL 60 SECOND")" | tee -a $LOG
  ;;
swap)
  say "swap: EVENT disable → AUTO_INCREMENT 여유 → RENAME → 차이분" | tee -a $LOG
  MY "ALTER EVENT crypto_db.cleanup_old_trades DISABLE"
  OLDMAX=$(MYN "SELECT max(trade_id) FROM crypto_db.crypto_trades"); MY "ALTER TABLE crypto_db.crypto_trades_p AUTO_INCREMENT = $((OLDMAX + 100000))"
  say "  old max trade_id=$OLDMAX, new AUTO_INCREMENT=$((OLDMAX + 100000))" | tee -a $LOG
  PRE_CH=$(CH "SELECT max(trade_id) FROM cdc_pipeline.crypto_trades")
  MY "RENAME TABLE crypto_db.crypto_trades TO crypto_db.crypto_trades_old, crypto_db.crypto_trades_p TO crypto_db.crypto_trades"; SWAP_T=$(date -u +%s)
  say "  renamed at $(date -u -d @$SWAP_T +%T)" | tee -a $LOG
  MY "SET sql_log_bin=0; INSERT IGNORE INTO crypto_db.crypto_trades (trade_id, market, trade_price, trade_volume, trade_amount, ask_bid, upbit_timestamp, sequential_id, created_at, best_ask_price, best_ask_size, best_bid_price, best_bid_size) SELECT trade_id, market, trade_price, trade_volume, trade_amount, ask_bid, upbit_timestamp, sequential_id, created_at, best_ask_price, best_ask_size, best_bid_price, best_bid_size FROM crypto_db.crypto_trades_old WHERE trade_id > (SELECT COALESCE(max(trade_id),0) FROM crypto_db.crypto_trades WHERE trade_id <= $OLDMAX)"
  say "  delta copied. old rows > $OLDMAX: $(MYN "SELECT count(*) FROM crypto_db.crypto_trades_old WHERE trade_id > $OLDMAX")" | tee -a $LOG
  sleep 30
  say "  verify: new inserts min id $(MYN "SELECT min(trade_id) FROM crypto_db.crypto_trades WHERE trade_id > $((OLDMAX + 100000 - 1))") (>= $((OLDMAX + 100000))), connect $(docker exec cdc-kafka-connect curl -s localhost:8083/connectors/mysql-cdc-connector/status | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['connector']['state'], [t['state'] for t in d['tasks']])"), ch first after pre-max $PRE_CH: $(CH "SELECT min(trade_id), count() FROM cdc_pipeline.crypto_trades WHERE trade_id > $PRE_CH FORMAT TSV" | tr '\t' '/'), ch 60s rows $(CH "SELECT count() FROM cdc_pipeline.crypto_trades WHERE flink_ts >= now() - INTERVAL 60 SECOND")" | tee -a $LOG
  docker logs cdc-kafka-connect --since 120s 2>&1 | grep -iE "Renaming|ERROR" | grep -v "errors\." | cut -c1-160 | tee -a $LOG
  ;;
finalize)
  say "finalize: 파티션 유지보수 EVENT 등록, 옛 EVENT 삭제, 상태" | tee -a $LOG
  MY "CREATE EVENT IF NOT EXISTS crypto_db.manage_trade_partitions_daily ON SCHEDULE EVERY 1 DAY STARTS (UTC_DATE() + INTERVAL 1 DAY + INTERVAL 5 MINUTE) DO CALL crypto_db.manage_trade_partitions()"
  MY "CALL crypto_db.manage_trade_partitions()"
  MY "DROP EVENT IF EXISTS crypto_db.cleanup_old_trades"
  MYN "SELECT partition_name, table_rows FROM information_schema.partitions WHERE table_schema='crypto_db' AND table_name='crypto_trades' ORDER BY partition_ordinal_position" | tr '\t' ':' | tr '\n' ' ' | tee -a $LOG; echo
  MYN "SELECT event_name, status, interval_value, interval_field FROM information_schema.events WHERE event_schema='crypto_db'" | tee -a $LOG
  ;;
rollback)
  say "rollback: 새→옛 차이분 복사, RENAME 되돌림, EVENT 복원" | tee -a $LOG
  MY "SET sql_log_bin=0; INSERT IGNORE INTO crypto_db.crypto_trades_old (trade_id, market, trade_price, trade_volume, trade_amount, ask_bid, upbit_timestamp, sequential_id, created_at, best_ask_price, best_ask_size, best_bid_price, best_bid_size) SELECT trade_id, market, trade_price, trade_volume, trade_amount, ask_bid, upbit_timestamp, sequential_id, created_at, best_ask_price, best_ask_size, best_bid_price, best_bid_size FROM crypto_db.crypto_trades WHERE trade_id > (SELECT max(trade_id) FROM crypto_db.crypto_trades_old)"
  MY "RENAME TABLE crypto_db.crypto_trades TO crypto_db.crypto_trades_p, crypto_db.crypto_trades_old TO crypto_db.crypto_trades"
  MY "ALTER EVENT crypto_db.cleanup_old_trades ENABLE"
  say "  rolled back" | tee -a $LOG
  ;;
status)
  MYN "SELECT table_name, table_rows, round(data_length/1048576) mb FROM information_schema.tables WHERE table_schema='crypto_db' AND table_name LIKE 'crypto_trades%'" | tr '\t' ' '
  ;;
esac
