import type { FlowDto, StatusFilter } from '../types';
import { toTestIdFragment } from '../utils';

export const FlowsView = ({
  flows,
  onViewDiagram,
  onOpenLongRunning,
  onOpenInstances,
  onOpenErrors,
}: {
  flows: FlowDto[];
  onViewDiagram: (flow: FlowDto) => void;
  onOpenLongRunning: (flowId: string) => void;
  onOpenInstances: (args: { search?: string; status?: StatusFilter; stage?: string; errorMessage?: string; incompleteOnly?: boolean }) => void;
  onOpenErrors: (args: { flow?: string; stage?: string; errorMessage?: string }) => void;
}) => (
  <div className="space-y-4">
    <h2 data-testid="flows-heading" className="text-xl font-bold text-zinc-50 mb-4">Flow Definitions</h2>
    {flows.map((flow) => (
      <div key={flow.flowId} data-testid={`flow-card-${flow.flowId}`} className="bg-zinc-900 border border-zinc-800 rounded-lg p-6">
        <div className="flex items-start justify-between mb-4">
          <div>
            <h3 className="text-lg font-bold text-zinc-50 font-mono">{flow.flowId}</h3>
            <p className="text-xs text-zinc-500 mt-1">{flow.stages.length} stages</p>
          </div>
          <div className="flex gap-2">
            <button
              data-testid={`flow-view-diagram-${flow.flowId}`}
              onClick={() => onViewDiagram(flow)}
              className="px-3 py-1 bg-zinc-800 hover:bg-zinc-700 rounded text-xs transition-colors"
            >
              View Diagram
            </button>
            {flow.longRunningCount > 0 && (
              <button
                data-testid={`flow-long-running-${flow.flowId}`}
                onClick={() => onOpenLongRunning(flow.flowId)}
                className="px-3 py-1 bg-red-500/20 hover:bg-red-500/30 text-red-400 rounded text-xs font-mono transition-colors"
              >
                {flow.longRunningCount} long inactive ⚠
              </button>
            )}
            <button
              data-testid={`flow-incomplete-${flow.flowId}`}
              onClick={() => onOpenInstances({ search: flow.flowId, status: 'all', stage: 'all', errorMessage: '', incompleteOnly: true })}
              className="px-3 py-1 bg-amber-500/20 hover:bg-amber-500/30 text-amber-400 rounded text-xs font-mono transition-colors"
            >
              {flow.notCompletedCount} incomplete →
            </button>
          </div>
        </div>

        {flow.stageBreakdown.length > 0 && (
          <div className="mb-4">
            <h4 className="text-xs font-bold text-zinc-500 uppercase tracking-wide mb-2">Active Stages</h4>
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-2">
              {flow.stageBreakdown.map((entry) => (
                <button
                  key={entry.stage}
                  data-testid={`flow-stage-${flow.flowId}-${toTestIdFragment(entry.stage)}`}
                  onClick={() => onOpenInstances({ search: flow.flowId, status: 'all', stage: entry.stage })}
                  className="bg-zinc-800/50 hover:bg-zinc-800 rounded p-3 text-left transition-colors group"
                >
                  <div className="text-xs text-zinc-400 mb-1 truncate group-hover:text-zinc-300">{entry.stage}</div>
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-bold text-zinc-200">{entry.totalCount}</span>
                    {entry.errorCount > 0 && (
                      <button
                        data-testid={`flow-stage-errors-${flow.flowId}-${toTestIdFragment(entry.stage)}`}
                        onClick={(event) => {
                          event.stopPropagation();
                          onOpenErrors({ flow: flow.flowId, stage: entry.stage });
                        }}
                        className="text-xs px-1.5 py-0.5 bg-red-500/20 hover:bg-red-500/30 text-red-400 rounded font-mono transition-colors"
                      >
                        {entry.errorCount} err
                      </button>
                    )}
                  </div>
                </button>
              ))}
            </div>
          </div>
        )}
      </div>
    ))}
  </div>
);