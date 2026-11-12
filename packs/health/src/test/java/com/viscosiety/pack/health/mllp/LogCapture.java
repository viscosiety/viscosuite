/*
 * Copyright 2026 Viscosiety B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.viscosiety.pack.health.mllp;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/** Collects the formatted messages one logger writes while it is open. */
final class LogCapture extends AbstractAppender implements AutoCloseable {

    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final Logger logger;

    private LogCapture(Logger logger) {
        super("capture-" + System.nanoTime(), null, null, true, Property.EMPTY_ARRAY);
        this.logger = logger;
    }

    static LogCapture of(Class<?> loggerClass) {
        Logger logger = (Logger) LogManager.getLogger(loggerClass);
        LogCapture capture = new LogCapture(logger);
        capture.start();
        logger.addAppender(capture);
        return capture;
    }

    @Override
    public void append(LogEvent event) {
        messages.add(event.getMessage().getFormattedMessage());
    }

    /** The first message containing {@code text}, waiting up to five seconds for it. */
    String waitForMessageContaining(String text) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            for (String message : messages) {
                if (message.contains(text)) {
                    return message;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no log message containing [" + text + "]; got " + messages);
    }

    @Override
    public void close() {
        logger.removeAppender(this);
        stop();
    }
}
