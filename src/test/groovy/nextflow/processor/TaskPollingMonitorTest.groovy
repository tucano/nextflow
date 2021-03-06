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

package nextflow.processor

import nextflow.Session
import nextflow.util.Duration
import spock.lang.Specification
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class TaskPollingMonitorTest extends Specification {

    def testCreate() {

        setup:
        def name = 'hello'
        def session = new Session( executor: [pollInterval: '1h', queueSize: 11, dumpInterval: '3h'] )

        def defSize = 99
        def defPollDuration = Duration.of('44s')
        when:
        def monitor = new TaskPollingMonitor(session, name, defSize, defPollDuration)
        then:
        monitor.name == name
        monitor.dispatcher == session.dispatcher
        monitor.pollIntervalMillis == Duration.of('1h').toMillis()
        monitor.queueSize == 11
        monitor.dumpInterval ==  Duration.of('3h')
        monitor.queue.remainingCapacity() == monitor.queueSize

    }


}
