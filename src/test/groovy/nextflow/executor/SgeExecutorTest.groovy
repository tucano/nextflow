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

import nextflow.processor.TaskConfig
import nextflow.processor.TaskRun
import spock.lang.Specification
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class SgeExecutorTest extends Specification {

    def 'test qsub cmd line' () {

        setup:
        def map = [:]
        map.queue = 'my-queue'
        map.maxMemory = '2GB'
        map.maxDuration = '3h'
        map.clusterOptions = '-extra opt'
        map.name = 'task'

        def config = new TaskConfig(map)
        def executor = [:] as SgeExecutor
        executor.taskConfig = config

        when:
        def wrapper = Paths.get('.job.sh')
        def task = new TaskRun(name: 'task x')
        task.workDirectory = Paths.get('/abc')

        then:
        executor.getSubmitCommandLine(task,wrapper) == 'qsub -wd /abc -N nf-task_x -o /dev/null -j y -terse -V -q my-queue -l h_rt=03:00:00 -l virtual_free=2G -extra opt .job.sh'.split(' ') as List

    }


    def testParseJobId() {

        when:
        def executor = [:] as SgeExecutor
        def textToParse = '''
            blah blah ..
            .. blah blah
            6472
            '''
        then:
        executor.parseJobId(textToParse) == '6472'
    }

    def testKillTaskCommand() {

        when:
        def executor = [:] as SgeExecutor
        then:
        executor.killTaskCommand(123) == ['qdel', '-j', '123'] as String[]

    }


    def testParseQueueStatus() {

        setup:
        def executor = [:] as SgeExecutor
        def text =
        """
        job-ID  prior   name       user         state submit/start at     queue                          slots ja-task-ID
        -----------------------------------------------------------------------------------------------------------------
        7548318 0.00050 nf-exonera pditommaso   r     02/10/2014 12:30:51 long@node-hp0214.linux.crg.es      1
        7548348 0.00050 nf-exonera pditommaso   r     02/10/2014 12:32:43 long@node-hp0204.linux.crg.es      1
        7548349 0.00050 nf-exonera pditommaso   hqw   02/10/2014 12:32:56 long@node-hp0303.linux.crg.es      1
        7548904 0.00050 nf-exonera pditommaso   qw    02/10/2014 13:07:09                                    1
        7548960 0.00050 nf-exonera pditommaso   Eqw   02/10/2014 13:08:11                                    1
        """.stripIndent().trim()


        when:
        def result = executor.parseQueueStatus(text)
        then:
        result.size() == 5
        result['7548318'] == AbstractGridExecutor.QueueStatus.RUNNING
        result['7548348'] == AbstractGridExecutor.QueueStatus.RUNNING
        result['7548349'] == AbstractGridExecutor.QueueStatus.HOLD
        result['7548904'] == AbstractGridExecutor.QueueStatus.PENDING
        result['7548960'] == AbstractGridExecutor.QueueStatus.ERROR

    }

    def testQueueStatusCommand() {

        setup:
        def executor = [:] as SgeExecutor

        expect:
        executor.queueStatusCommand(null) == ['qstat']
        executor.queueStatusCommand('long') == ['qstat','-q','long']

    }

}
