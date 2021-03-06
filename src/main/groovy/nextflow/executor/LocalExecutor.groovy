/*
 * Copyright (c) 2012, the authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.executor
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Future

import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.processor.TaskConfig
import nextflow.processor.TaskHandler
import nextflow.processor.TaskMonitor
import nextflow.processor.TaskPollingMonitor
import nextflow.processor.TaskRun
import nextflow.script.ScriptType
import nextflow.util.Duration
import nextflow.util.PosixProcess
import org.apache.commons.io.IOUtils
import org.codehaus.groovy.runtime.IOGroovyMethods
/**
 * Executes the specified task on the locally exploiting the underlying Java thread pool
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
class LocalExecutor extends AbstractExecutor {

    @Override
    protected TaskMonitor createTaskMonitor() {

        final defSize = Math.max( Runtime.getRuntime().availableProcessors()-1, 1 )
        return new TaskPollingMonitor(session, name, defSize, Duration.of('50 ms'))

    }

    @Override
    def TaskHandler createTaskHandler(TaskRun task) {
        assert task
        assert task.workDirectory

        /*
         * when it is a native groovy code, use the native handler
         */
        if( task.type == ScriptType.GROOVY ) {
            return new NativeTaskHandler(task,taskConfig,session)
        }

        /*
         * otherwise as a bash script
         */
        final bash = new BashWrapperBuilder(task)
        bash.environment = task.processor.getProcessEnvironment()
        bash.environment.putAll( task.getInputEnvironment() )

        // staging input files
        bash.stagingScript = {
            final files = task.getInputFiles()
            final staging = stagingFilesScript(files)
            return staging
        }

        // unstage script
        bash.unstagingScript = {
            return unstageOutputFilesScript(task)
        }

        // create the wrapper script
        bash.build()
        return new LocalTaskHandler(task,taskConfig,session)
    }


}


/**
 * A process wrapper adding the ability to access to the Posix PID
 * and the {@code hasExited} flag
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
class LocalTaskHandler extends TaskHandler {

    private final startTimeMillis = System.currentTimeMillis()

    private final Path exitFile

    private final Long maxDurationMillis

    private final Path wrapperFile

    private final Path outputFile

    private PosixProcess process

    private boolean destroyed

    private Session session

    LocalTaskHandler( TaskRun task, TaskConfig taskConfig, Session session  ) {
        super(task, taskConfig)
        // create the task handler
        this.exitFile = task.getCmdExitFile()
        this.outputFile = task.getCmdOutputFile()
        this.wrapperFile = task.getCmdWrapperFile()
        this.maxDurationMillis = taskConfig.maxDuration?.toMillis()
        this.session = session
    }



    @Override
    def void submit() {

        // the cmd list to launch it
        List cmd = new ArrayList(taskConfig.shell ?: 'bash' as List ) << wrapperFile.getName()
        log.trace "Launch cmd line: ${cmd.join(' ')}"

        ProcessBuilder builder = new ProcessBuilder()
                .directory(task.workDirectory.toFile())
                .command(cmd)
                .redirectErrorStream(true)

        // -- start the execution and notify the event to the monitor
        process = new PosixProcess(builder.start())

        // handle in/out
        pipeStdInput()

        // mark as submitted -- transition to STARTED has to be managed by the scheduler
        status = Status.SUBMITTED
    }

    /**
     * Pipe the process input to the running process and save the stdout to the specified *output* file
     */
    private pipeStdInput() {

        /*
         * Pipe the input data using a parallel thread
         */
        final input = task.stdin
        if( !input ) { return }

        session.getExecService().submit {
            try {
                IOGroovyMethods.withStream(new BufferedOutputStream(process.getOutputStream())) { writer -> writer << input }
            }
            catch( Exception e ) {
                log.warn "Unable to pipe input data for process: ${task.name}"
            }
        }

    }


    long elapsedTimeMillis() {
        System.currentTimeMillis() - startTimeMillis
    }

    /**
     * Check if the submitted job has started
     */
    @Override
    boolean checkIfRunning() {

        if( isSubmitted() && process != null ) {
            status = Status.RUNNING
            return true
        }

        return false
    }

    /**
     * Check if the submitted job has terminated its execution
     */
    @Override
    boolean checkIfCompleted() {

        if( !isRunning() ) { return false }

        def done = process.hasExited()
        if( done ) {
            task.exitStatus = process.exitValue()
            task.stdout = outputFile
            status = Status.COMPLETED
            destroy()
            return true
        }

        if( maxDurationMillis ) {
            /*
             * check if the task exceed max duration time
             */
            if( elapsedTimeMillis() > maxDurationMillis ) {
                destroy()
                task.exitStatus = process.exitValue()
                task.stdout = outputFile
                status = Status.COMPLETED

                // signal has completed
                return true
            }
        }

        return false
    }


    /**
     * Force the submitted job to quit
     */
    @Override
    void kill() { destroy() }

    /**
     * Destroy the process handler, closing all associated streams
     */
    void destroy() {

        if( destroyed ) { return }

        IOUtils.closeQuietly(process.getInputStream())
        IOUtils.closeQuietly(process.getOutputStream())
        IOUtils.closeQuietly(process.getErrorStream())
        process.destroy()
        destroyed = true
    }

}

/**
 * Executes a native piece of groovy code
 */
@Slf4j
class NativeTaskHandler extends TaskHandler {

    def Future<Throwable> result

    private Session session

    protected NativeTaskHandler(TaskRun task, TaskConfig taskConfig, Session session) {
        super(task, taskConfig)
        this.session = session
    }


    @Override
    void submit() {
        // submit for execution by using session executor service
        // it returns an error when everything is OK
        // of the exception throw in case of error
        result = session.getExecService().submit({
            try  {
                return task.code.call()
            }
            catch( Throwable error ) {
                return error
            }

        } as Callable)
        status = Status.SUBMITTED
    }

    @Override
    boolean checkIfRunning() {
        if( isSubmitted() && result != null ) {
            status = Status.RUNNING
            return true
        }

        return false
    }

    @Override
    boolean checkIfCompleted() {
        if( isRunning() && result.isDone() ) {
            status = Status.COMPLETED
            if( result.get() instanceof Throwable ) {
                task.error = result.get()
            }
            else {
                task.stdout = result.get()
            }
            return true
        }
        return false
    }

    @Override
    void kill() {
        if( result ) result.cancel(true)
    }

}


