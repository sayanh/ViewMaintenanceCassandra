package de.tum.viewmaintenance.EvaluationLogging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by shazra on 10/2/15.
 */
public final class StatsLogging {
    private static final Logger logger = LoggerFactory.getLogger(StatsLogging.class);

    /**
     * Logging of system stats
     **/
    public static void logSystemInfo(String message) {

        Double memoryFree = new Double(Runtime.getRuntime().freeMemory());
        Double memoryAvailable = new Double(Runtime.getRuntime().maxMemory());

        logger.info("### Analysis | Memory usage {} | {}", message, ((memoryAvailable - memoryFree) /
                memoryAvailable) * 100.0);
        logger.info("### Analysis | Memory free {} | {}", message, (memoryFree / memoryAvailable) * 100.0);

        logger.info("### Analysis | Memory usage(in bytes) {} | {}", message, (memoryAvailable - memoryFree));

        logger.info("### Analysis | Memory free(in bytes) {} | {}", message, memoryFree);
    }
}
