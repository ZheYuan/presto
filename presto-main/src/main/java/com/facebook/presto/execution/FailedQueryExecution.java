/*
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
package com.facebook.presto.execution;

import com.facebook.presto.execution.StateMachine.StateChangeListener;
import com.facebook.presto.spi.ConnectorSession;
import io.airlift.units.Duration;

import java.net.URI;
import java.util.concurrent.Executor;

public class FailedQueryExecution
        implements QueryExecution
{
    private final QueryInfo queryInfo;

    public FailedQueryExecution(QueryId queryId, String query, ConnectorSession session, URI self, Executor executor, Throwable cause)
    {
        QueryStateMachine queryStateMachine = new QueryStateMachine(queryId, query, session, self, executor);
        queryStateMachine.fail(cause);

        queryInfo = queryStateMachine.getQueryInfo(null);
    }

    @Override
    public QueryInfo getQueryInfo()
    {
        return queryInfo;
    }

    @Override
    public void start()
    {
        // no-op
    }

    @Override
    public Duration waitForStateChange(QueryState currentState, Duration maxWait)
            throws InterruptedException
    {
        return maxWait;
    }

    @Override
    public void addStateChangeListener(StateChangeListener<QueryState> stateChangeListener)
    {
        stateChangeListener.stateChanged(QueryState.FAILED);
    }

    @Override
    public void cancel()
    {
        // no-op
    }

    @Override
    public void fail(Throwable cause)
    {
        // no-op
    }

    @Override
    public void cancelStage(StageId stageId)
    {
        // no-op
    }

    @Override
    public void recordHeartbeat()
    {
        // no-op
    }
}
