/*
 * 3D City Database - The Open Source CityGML Database
 * http://www.3dcitydb.org/
 *
 * Copyright 2013 - 2019
 * Chair of Geoinformatics
 * Technical University of Munich, Germany
 * https://www.gis.bgu.tum.de/
 *
 * The 3D City Database is jointly developed with the following
 * cooperation partners:
 *
 * virtualcitySYSTEMS GmbH, Berlin <http://www.virtualcitysystems.de/>
 * M.O.S.S. Computer Grafik Systeme GmbH, Taufkirchen <http://www.moss.de/>
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
package org.citydb.log;

import org.citydb.config.project.global.LogFileMode;
import org.citydb.config.project.global.LogLevel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Logger {
	private static final Logger instance = new Logger();

	private ConsoleLogger consoleLogger;
	private LogLevel consoleLevel = LogLevel.INFO;
	private LogLevel fileLevel = LogLevel.INFO;
	private BufferedWriter writer;

	private Logger() {
		consoleLogger = new DefaultConsoleLogger();
	}

	public static Logger getInstance() {
		return instance;
	}

	public void setConsoleLogger(ConsoleLogger consoleLogger) {
		if (consoleLogger != null)
			this.consoleLogger = consoleLogger;
	}

	public void setConsoleLogLevel(LogLevel level) {
		consoleLevel = level;
	}

	public void setFileLogLevel(LogLevel level) {
		fileLevel = level;
	}

	public LogLevel getConsoleLogLevel() {
		return consoleLevel;
	}

	public LogLevel getFileLogLevel() {
		return fileLevel;
	}

	public String getPrefix(LogLevel level) {
		return "[" +
				LocalDateTime.now().withNano(0).format(DateTimeFormatter.ISO_LOCAL_TIME) +
				" " +
				level.name() +
				"] ";
	}

	public void log(LogLevel level, String msg) {
		msg = getPrefix(level) + msg;

		if (consoleLevel.ordinal() >= level.ordinal())
			consoleLogger.log(level, msg);

		if (fileLevel.ordinal() >= level.ordinal())
			logToFile(msg);
	}

	public void logToFile(String msg) {
		if (writer != null) {
			try {
				writer.write(msg);
				writer.newLine();
				writer.flush();
			} catch (IOException e) {
				//
			}
		}
	}

	private void log(LogLevel level, String msg, Throwable e) {
		log(level, msg);
		if (e != null) {
			do {
				if (e.getMessage() != null)
					log(level, "Caused by: " + e.getClass().getName() + ": " + e.getMessage());
			} while ((e = e.getCause()) != null);
		}
	}

	public void debug(String msg) {		
		log(LogLevel.DEBUG, msg);
	}

	public void info(String msg) {
		log(LogLevel.INFO, msg);
	}

	public void warn(String msg) {
		log(LogLevel.WARN, msg);
	}

	public void warn(String msg, Throwable e) {
		log(LogLevel.WARN, msg, e);
	}

	public void error(String msg) {
		log(LogLevel.ERROR, msg);
	}

	public void error(String msg, Throwable e) {
		log(LogLevel.ERROR, msg, e);
	}

	public void print(String msg) {
		consoleLogger.log(msg);
		logToFile(msg);
	}

	public void logStackTrace(Throwable t) {
		StringWriter writer = new StringWriter();
		t.printStackTrace(new PrintWriter(writer, true));
		log(LogLevel.ERROR, writer.toString());
	}

	public boolean appendLogFile(Path logFile, LogFileMode mode) {
		if (Files.exists(logFile) && Files.isDirectory(logFile)) {
			logFile = logFile.resolve(getDefaultLogFileName());
		} else if (!Files.exists(logFile.getParent())) {
			try {
				Files.createDirectories(logFile.getParent());
			} catch (IOException e) {
				error("Failed to create log file directory '" + logFile.getParent().toAbsolutePath() + "'.");
				return false;
			}
		}
		
		try {
			detachLogFile();
			info("Writing log messages to file: '" + logFile.toAbsolutePath() + "'");
			writer = Files.newBufferedWriter(logFile,
					StandardCharsets.UTF_8,
					StandardOpenOption.CREATE,
					StandardOpenOption.WRITE,
					mode == LogFileMode.TRUNCATE ?
							StandardOpenOption.TRUNCATE_EXISTING :
							StandardOpenOption.APPEND);

			logToFile("*** Starting new log file session on " + LocalDateTime.now()
					.withNano(0)
					.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

			return true;
		} catch (IOException e) {
			error("Failed to open log file '" + logFile + "': " + e.getMessage());
			error("Not writing log messages to file.");
			return false;
		}
	}

	public void detachLogFile() {
		if (writer != null) {
			try {
				info("Stopped writing log messages to log file.");
				writer.close();
			} catch (IOException e) {
				//
			} finally {
				writer = null;
			}
		}
	}

	public String getDefaultLogFileName() {
		return "impexp-" +
				LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE) +
				".log";
	}
}
