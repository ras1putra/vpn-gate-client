import time
import logging
from app.database import init_db
from app.scraper import fetch_and_sync_vpngate
from app.prober import probe_all_unverified_servers
from app.validator import run_3_layered_security_check

logger = logging.getLogger(__name__)


def run_full_pipeline():
    logger.info("================ STARTING PIPELINE CYCLE ================")
    try:
        # Phase 1: Ingest
        fetch_and_sync_vpngate()

        # Phase 2: Liveness & Egress IP Probing
        probe_all_unverified_servers()

        # Phase 3: 3-Layered Security Check
        run_3_layered_security_check()
    except Exception as e:
        logger.error(f"Error executing pipeline cycle: {e}")
    logger.info("================ PIPELINE CYCLE FINISHED ================")


def start_scheduler_loop(interval_minutes: int = 15):
    init_db()
    logger.info(f"Starting pipeline scheduler background loop (every {interval_minutes} minutes)...")

    # Run initial cycle on startup
    run_full_pipeline()

    while True:
        time.sleep(interval_minutes * 60)
        run_full_pipeline()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
    start_scheduler_loop()
