/*
 * Copyright 2013-2018 the original author or authors.
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
package org.asciidoctor.gradle.remote

import groovy.transform.CompileStatic
import org.asciidoctor.Options
import org.asciidoctor.ast.Cursor
import org.asciidoctor.gradle.internal.ExecutorConfiguration
import org.asciidoctor.gradle.internal.ExecutorConfigurationContainer
import org.asciidoctor.gradle.internal.ExecutorLogLevel
import org.asciidoctor.log.LogHandler
import org.asciidoctor.log.LogRecord

import java.util.regex.Pattern

/** Base class for building claspath-isolated executors for Asciidoctor.
 *
 * @since 2.0.0
 * @author Schalk W. Cronjé
 */
@CompileStatic
abstract class ExecutorBase {

    private final List<String> warningMessages = []
    private final List<Pattern> messagePatterns = []

    /**  List of configurations that are required for execution.
     *
     */
    protected final List<ExecutorConfiguration> runConfigurations

    /** Initialise the executor.
     *
     * @param execConfig Container of executor configurations
     */
    protected ExecutorBase(final ExecutorConfigurationContainer execConfig) {
        this.runConfigurations = execConfig.configurations
    }

    /** Normalises Asciidoctor options for a given source file.
     *
     * Relativizes certain attributes and ensure specific options for backend, sage mode and output
     * directory are in place.
     *
     * @param file Source file to be converted
     * @param runConfiguration The current executor configuration
     * @return Asciidoctor options
     */
    @SuppressWarnings(['Instanceof','DuplicateStringLiteral'])
    protected
    Map<String, Object> normalisedOptionsFor(final File file, ExecutorConfiguration runConfiguration) {

        Map<String, Object> mergedOptions = [:]

        runConfiguration.with {
            final String srcRelative = getRelativePath(file.parentFile, sourceDir)

            mergedOptions.putAll(options)
            mergedOptions.putAll([
                (Options.BACKEND) : backendName,
                (Options.IN_PLACE): false,
                (Options.SAFE)    : safeModeLevel,
                (Options.TO_DIR)  : (srcRelative.empty ? outputDir : new File(outputDir,srcRelative)).absolutePath,
                (Options.MKDIRS)  : true
            ])

            mergedOptions[Options.BASEDIR] = (baseDir ?: file.parentFile).absolutePath

            if (mergedOptions.containsKey(Options.TO_FILE)) {
                Object toFileValue = mergedOptions[Options.TO_FILE]
                Object toDirValue = mergedOptions.remove(Options.TO_DIR)
                File toFile = toFileValue instanceof File ? (File) toFileValue : new File(toFileValue.toString())
                File toDir = toDirValue instanceof File ? (File) toDirValue : new File(toDirValue.toString())
                mergedOptions[Options.TO_FILE] = new File(toDir, toFile.name).absolutePath
            }

            // Note: Directories passed as relative to work around issue #83
            // Asciidoctor cannot handle absolute paths in Windows properly
            Map<String, Object> newAttrs = [:]
            newAttrs.putAll(attributes)
            newAttrs['gradle-projectdir'] = getRelativePath(projectDir, file.parentFile)
            newAttrs['gradle-rootdir'] = getRelativePath(rootDir, file.parentFile)

            if(legacyAttributes) {
                newAttrs['projectdir'] = newAttrs['gradle-projectdir']
                newAttrs['rootdir'] = newAttrs['gradle-rootdir']
            }

            mergedOptions[Options.ATTRIBUTES] = newAttrs
        }

        mergedOptions
    }

    /**
     * Returns the path of one File relative to another.
     *
     * @param target the target directory
     * @param base the base directory
     * @return target's path relative to the base directory
     * @throws IOException if an error occurs while resolving the files' canonical names
     */
    protected String getRelativePath(File target, File base) throws IOException {
        base.toPath().relativize(target.toPath()).toFile().toString()
    }

    /** Rehydrates extensions that were serialised.
     *
     * @param registry Asciidoctor GroovyDSL registry instance.
     * @param exts List of extensions to rehydrate.
     * @return
     */
    protected List<Object> rehydrateExtensions(final Object registry, final List<Object> exts) {
        exts.collect {
            switch (it) {
                case Closure:
                    Closure rehydrated = ((Closure) it).rehydrate(registry, null, null)
                    rehydrated.resolveStrategy = Closure.DELEGATE_ONLY
                    (Object) rehydrated
                    break
                default:
                    it
            }
        } as List<Object>
    }

    /** Creates a log handler for Asciidoctor
     *
     * @param required The required level of logging
     * @return A log handler instance suitable for registering with AsciidoctorJ.
     */
    protected LogHandler getLogHandler(ExecutorLogLevel required) {
        int requiredLevel = required.level
        new LogHandler() {
            @Override
            void log(LogRecord logRecord) {
                ExecutorLogLevel logLevel = LogSeverityMapper.translateAsciidoctorLogLevel(logRecord.severity)

                if (logLevel.level >= requiredLevel) {

                    String msg = logRecord.message
                    Cursor cursor = logRecord.cursor
                    if (cursor) {
                        msg = "${msg} :: ${cursor.path ?: ''} :: ${cursor.dir ?: ''}/${cursor.file ?: ''}:${cursor.lineNumber >= 0 ? cursor.lineNumber.toString() : ''}"
                    }
                    if (logRecord.sourceFileName) {
                        msg = "${msg} (${logRecord.sourceFileName}${logRecord.sourceMethodName ? (':' + logRecord.sourceMethodName) : ''})"
                    }

                    logMessage(logLevel, msg)
                }

                addMatchingMessage(logRecord.message)
            }
        }
    }

    /** Performs the actual logging of a message.
     *
     * it calls an implementation specifc to the kind of executor to log the message.
     *
     * @param logLevel The level of the message
     * @param msg Message to be logged
     */
    abstract protected void logMessage(ExecutorLogLevel logLevel, final String msg)

    /** Adds a warning message that fits a pattern.
     *
     * @param msg
     */
    protected void addMatchingMessage(final String msg) {
        if (!this.messagePatterns.empty) {
            if (this.messagePatterns.any { msg =~ it }) {
                this.warningMessages.add msg
            }
        }
    }

    /** The list of warning messages that was recorded during the conversion.
     *
     * @return
     */
    protected List<String> getWarningMessages() {
        this.warningMessages
    }

    /** Patterns for matching log messages as errors
     *
     * @param patterns List of patterns. Can be empty.
     */
    protected void resetMessagePatternsTo(final List<Pattern> patterns) {
        this.messagePatterns.clear()
        this.messagePatterns.addAll(patterns)
    }

    /** If any warning message was set, fail with an exception.
     *
     */
    @SuppressWarnings('UnnecessaryGString')
    protected void failOnWarnings() {
        if (!warningMessages.empty) {
            final String msg = "ERROR: The following messages from AsciidoctorJ are treated as errors:\n" + warningMessages.join("\n- ")
            throw new AsciidoctorRemoteExecutionException(msg)
        }
    }


}
