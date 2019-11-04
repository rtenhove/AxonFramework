/*
 * Copyright (c) 2010-2019. Axon Framework
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

package org.axonframework.axonserver.connector.processor.schedule;

import org.axonframework.axonserver.connector.processor.FakeEventProcessorInfoSource;
import org.axonframework.axonserver.connector.utils.AssertUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

/**
 * Created by Sara Pellegrini on 23/03/2018.
 * sara.pellegrini@gmail.com
 */
public class ScheduledEventProcessorInfoSourceTest {

    private ScheduledEventProcessorInfoSource scheduled;
    private FakeEventProcessorInfoSource delegate;

    @Before
    public void setUp() throws Exception {
        delegate = new FakeEventProcessorInfoSource();
        scheduled = new ScheduledEventProcessorInfoSource(50, 30, delegate);
    }

    @After
    public void tearDown() throws Exception {
        scheduled.shutdown();
    }

    @Test
    public void notifyInformation() throws InterruptedException {
        scheduled.start();
        TimeUnit.MILLISECONDS.sleep(50);
        AssertUtils.assertWithin(100, TimeUnit.MILLISECONDS, () -> assertEquals(2, delegate.notifyCalls()));
    }


}
