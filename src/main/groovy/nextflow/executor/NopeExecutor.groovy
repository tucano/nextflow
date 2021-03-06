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

import java.nio.file.Paths

import groovy.util.logging.Slf4j
import nextflow.processor.TaskHandler
import nextflow.processor.TaskPollingMonitor
import nextflow.processor.TaskMonitor
import nextflow.processor.TaskRun
import nextflow.util.Duration

/**
 * Dummy executor, only for test purpose
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
class NopeExecutor extends AbstractExecutor {

    @Override
    protected TaskMonitor createTaskMonitor() {
        return new TaskPollingMonitor(session, name, 5, Duration.of('50ms'))
    }

    @Override
    TaskHandler createTaskHandler(TaskRun task) {
        return new NopeTaskHandler(task)
    }

}


@Slf4j
class NopeTaskHandler extends TaskHandler {

    protected NopeTaskHandler(TaskRun task) {
        super(task,null)
    }

    @Override
    void submit() {
        log.info ">> launching nope process: ${task}"
        task.workDirectory = Paths.get('.').toAbsolutePath()
        status = Status.SUBMITTED
        task.stdout = task.script
        task.exitStatus = 0
    }

    @Override
    boolean checkIfRunning() {
        log.debug "isRunning: $status"
        if( isSubmitted() ) {
            status = Status.RUNNING
            return true
        }
        return false
    }

    @Override
    boolean checkIfCompleted() {
        log.debug "isTerminated: $status"
        if( isRunning() ) {
            status = Status.COMPLETED
            return true
        }
        false
    }

    @Override
    void kill() { }

}

