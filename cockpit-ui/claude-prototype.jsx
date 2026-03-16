import React, { useState, useMemo, useEffect, useRef } from 'react';
import { Play, AlertCircle, CheckCircle, Clock, RefreshCw, ChevronRight, X, Search, Database } from 'lucide-react';

const StatusBadge = ({ status }) => {
  const config = {
    PENDING: { bg: 'bg-amber-500/20', text: 'text-amber-400', label: 'Pending' },
    RUNNING: { bg: 'bg-blue-500/20', text: 'text-blue-400', label: 'Running' },
    COMPLETED: { bg: 'bg-emerald-500/20', text: 'text-emerald-400', label: 'Completed' },
    ERROR: { bg: 'bg-red-500/20', text: 'text-red-400', label: 'Error' },
    CANCELLED: { bg: 'bg-zinc-500/20', text: 'text-zinc-400', label: 'Cancelled' }
  };
  
  const style = config[status];
  
  return (
    <span className={`px-2 py-1 rounded text-xs font-mono ${style.bg} ${style.text} flex items-center gap-1`}>
      {status === 'ERROR' && <AlertCircle size={12} />}
      {status === 'COMPLETED' && <CheckCircle size={12} />}
      {status === 'PENDING' && <Clock size={12} />}
      {status === 'RUNNING' && <Play size={12} />}
      {style.label}
    </span>
  );
};

const FlowLiteCockpit = () => {
  const mockFlows = [
    {
      id: 'order-confirmation',
      stages: ['InitializingConfirmation', 'WaitingForConfirmation', 'RemovingFromConfirmationQueue', 'InformingCustomer'],
      diagram: 'stateDiagram-v2\n[*] --> InitializingConfirmation\nInitializingConfirmation --> WaitingForConfirmation\nWaitingForConfirmation --> RemovingFromConfirmationQueue: ConfirmedDigitally\nRemovingFromConfirmationQueue --> InformingCustomer\nWaitingForConfirmation --> InformingCustomer: ConfirmedPhysically\nInformingCustomer --> [*]'
    },
    {
      id: 'employee-onboarding',
      stages: ['CreateUserInSystem', 'ActivateEmployee', 'GenerateDocuments', 'WaitingForDocumentsSigned', 'UpdateHRSystem'],
      diagram: 'stateDiagram-v2\n[*] --> CreateUserInSystem\nCreateUserInSystem --> ActivateEmployee\nActivateEmployee --> GenerateDocuments\nGenerateDocuments --> WaitingForDocumentsSigned\nWaitingForDocumentsSigned --> UpdateHRSystem\nUpdateHRSystem --> [*]'
    }
  ];

  const mockInstances = useMemo(() => {
    const instances = [];
    const statuses = ['PENDING', 'RUNNING', 'COMPLETED', 'ERROR', 'CANCELLED'];
    
    const errorTypes = [
      { 
        msg: 'Network timeout connecting to payment gateway', 
        category: 'NETWORK', 
        userRetriable: false, 
        autoRetry: true,
        stackTrace: 'java.net.SocketTimeoutException: connect timed out\n    at PaymentGateway.charge(PaymentGateway.kt:42)\n    at ProcessPaymentAction.invoke(OrderActions.kt:87)\n    at FlowEngine.executeAction(FlowEngine.kt:234)\n    at FlowEngine.processTickLoop(FlowEngine.kt:156)'
      },
      { 
        msg: 'Database connection pool exhausted', 
        category: 'DATABASE', 
        userRetriable: false, 
        autoRetry: false,
        stackTrace: 'java.sql.SQLException: Cannot get connection from pool\n    at HikariPool.getConnection(HikariPool.kt:78)\n    at OrderRepository.save(OrderRepository.kt:23)\n    at ProcessOrderAction.invoke(OrderActions.kt:45)\n    at FlowEngine.executeAction(FlowEngine.kt:234)'
      },
      { 
        msg: 'Invalid email format in order data', 
        category: 'VALIDATION', 
        userRetriable: true, 
        autoRetry: false,
        stackTrace: 'io.flowlite.ValidationException: Email format invalid\n    at EmailValidator.validate(Validators.kt:12)\n    at ValidateOrderAction.invoke(OrderActions.kt:67)\n    at FlowEngine.executeAction(FlowEngine.kt:234)\n    at FlowEngine.processTickLoop(FlowEngine.kt:156)'
      },
      { 
        msg: 'External service unavailable (503)', 
        category: 'NETWORK', 
        userRetriable: false, 
        autoRetry: true,
        stackTrace: 'retrofit2.HttpException: HTTP 503 Service Unavailable\n    at InventoryService.checkStock(InventoryService.kt:55)\n    at CheckInventoryAction.invoke(OrderActions.kt:112)\n    at FlowEngine.executeAction(FlowEngine.kt:234)\n    at FlowEngine.processTickLoop(FlowEngine.kt:156)'
      },
      { 
        msg: 'Business rule violation: order total below minimum', 
        category: 'BUSINESS_RULE', 
        userRetriable: true, 
        autoRetry: false,
        stackTrace: 'io.flowlite.BusinessRuleException: Order total $15.00 below minimum $25.00\n    at OrderValidator.validateMinimum(OrderValidator.kt:34)\n    at ValidateOrderAction.invoke(OrderActions.kt:78)\n    at FlowEngine.executeAction(FlowEngine.kt:234)\n    at FlowEngine.processTickLoop(FlowEngine.kt:156)'
      },
      { 
        msg: 'Unexpected null value in required field', 
        category: 'SYSTEM', 
        userRetriable: null, 
        autoRetry: null,
        stackTrace: 'java.lang.NullPointerException: Required field cannot be null\n    at DataProcessor.process(DataProcessor.kt:89)\n    at ProcessDataAction.invoke(OrderActions.kt:134)\n    at FlowEngine.executeAction(FlowEngine.kt:234)\n    at FlowEngine.processTickLoop(FlowEngine.kt:156)'
      }
    ];
    
    mockFlows.forEach(flow => {
      for (let i = 0; i < 20; i++) {
        const status = statuses[Math.floor(Math.random() * statuses.length)];
        const stageIndex = Math.floor(Math.random() * flow.stages.length);
        const errorType = status === 'ERROR' ? errorTypes[Math.floor(Math.random() * errorTypes.length)] : null;
        
        instances.push({
          id: flow.id + '-' + i,
          flowId: flow.id,
          stage: (status === 'CANCELLED' || status === 'COMPLETED') ? '' : flow.stages[stageIndex],
          status: status,
          createdAt: new Date(Date.now() - Math.random() * 7 * 24 * 60 * 60 * 1000),
          updatedAt: new Date(Date.now() - Math.random() * 24 * 60 * 60 * 1000),
          errorMessage: errorType ? 'Action execution failed: ' + errorType.msg : null,
          stackTrace: errorType ? errorType.stackTrace : null,
          errorCategory: errorType ? errorType.category : null,
          userRetriable: errorType ? errorType.userRetriable : null,
          autoRetry: errorType ? errorType.autoRetry : null,
          retryCount: status === 'ERROR' ? Math.floor(Math.random() * 3) : 0
        });
      }
    });

    return instances;
  }, []);

  const generateHistory = (instanceId) => {
    const instance = mockInstances.find(i => i.id === instanceId);
    if (!instance) return [];
    
    const flow = mockFlows.find(f => f.id === instance.flowId);
    const history = [];
    
    // Generate realistic history based on processTickLoop() execution
    const stagesVisited = flow.stages.slice(0, flow.stages.indexOf(instance.stage) + 1);
    
    let currentTime = instance.createdAt.getTime();
    
    stagesVisited.forEach((stage, idx) => {
      // STAGE_ENTERED - when processTickLoop moves to new stage
      history.push({
        timestamp: new Date(currentTime),
        event: 'STAGE_ENTERED',
        stage: stage,
        details: 'Entered stage ' + stage,
        stackTrace: null
      });
      currentTime += 1000 + Math.random() * 2000;
      
      // ACTION_STARTED - when action begins execution
      if (Math.random() > 0.3) {
        history.push({
          timestamp: new Date(currentTime),
          event: 'ACTION_STARTED',
          stage: stage,
          details: 'Executing action for stage ' + stage,
          stackTrace: null
        });
        currentTime += 500 + Math.random() * 1500;
        
        // ACTION_COMPLETED - when action finishes successfully
        history.push({
          timestamp: new Date(currentTime),
          event: 'ACTION_COMPLETED',
          stage: stage,
          details: 'Action completed successfully',
          stackTrace: null
        });
        currentTime += 300;
      }
      
      // TRANSITION_COMPLETED - when moving to next stage
      if (idx < stagesVisited.length - 1) {
        history.push({
          timestamp: new Date(currentTime),
          event: 'TRANSITION_COMPLETED',
          stage: stage,
          details: 'Transitioned from ' + stage + ' to ' + stagesVisited[idx + 1],
          stackTrace: null
        });
        currentTime += 200;
      }
      
      // EVENT_RECEIVED - simulate event arrival for event-driven stages
      if (stage.includes('Waiting') && Math.random() > 0.6) {
        history.push({
          timestamp: new Date(currentTime),
          event: 'EVENT_RECEIVED',
          stage: stage,
          details: 'Event received and queued for processing',
          stackTrace: null
        });
        currentTime += 500;
        
        history.push({
          timestamp: new Date(currentTime),
          event: 'EVENT_CONSUMED',
          stage: stage,
          details: 'Event consumed and processed',
          stackTrace: null
        });
        currentTime += 300;
      }
    });
    
    // Add past errors if instance had retries
    if (instance.retryCount > 0 && instance.status === 'ERROR') {
      const errorTypes = [
        { 
          msg: 'Temporary network glitch', 
          stackTrace: 'java.net.SocketTimeoutException: Read timed out\n    at SocketInputStream.read(SocketInputStream.java:123)\n    at HttpClient.execute(HttpClient.kt:89)\n    at ApiService.call(ApiService.kt:45)' 
        },
        { 
          msg: 'Database deadlock detected', 
          stackTrace: 'java.sql.SQLException: Deadlock found when trying to get lock\n    at TransactionManager.commit(TransactionManager.kt:67)\n    at Repository.save(Repository.kt:34)' 
        }
      ];
      
      for (let i = 0; i < instance.retryCount; i++) {
        const pastError = errorTypes[Math.floor(Math.random() * errorTypes.length)];
        currentTime += 10000 + Math.random() * 30000;
        
        history.push({
          timestamp: new Date(currentTime),
          event: 'ERROR_OCCURRED',
          stage: instance.stage,
          details: 'Previous error (retry ' + (i + 1) + '): ' + pastError.msg,
          stackTrace: pastError.stackTrace
        });
        currentTime += 5000;
        
        history.push({
          timestamp: new Date(currentTime),
          event: 'RETRY_ATTEMPTED',
          stage: instance.stage,
          details: 'Retry attempt ' + (i + 1) + ' initiated',
          stackTrace: null
        });
        currentTime += 1000;
      }
    }
    
    // Add current error if instance is in ERROR state
    if (instance.status === 'ERROR') {
      currentTime += 1000;
      history.push({
        timestamp: new Date(currentTime),
        event: 'ERROR_OCCURRED',
        stage: instance.stage,
        details: instance.errorMessage,
        stackTrace: instance.stackTrace
      });
    }
    
    return history.sort((a, b) => b.timestamp - a.timestamp);
  };

  const [activeView, setActiveView] = useState('flows');
  const [selectedInstance, setSelectedInstance] = useState(null);
  const [selectedFlowForDiagram, setSelectedFlowForDiagram] = useState(null);
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('all');
  const [stageFilter, setStageFilter] = useState('all');
  const [errorMessageFilter, setErrorMessageFilter] = useState('');
  const [showIncompleteOnly, setShowIncompleteOnly] = useState(false);
  const [errorFlowFilter, setErrorFlowFilter] = useState('all');
  const [errorStageFilter, setErrorStageFilter] = useState('all');
  const [errorMessageFilterErrors, setErrorMessageFilterErrors] = useState('');
  const [longRunningFlowFilter, setLongRunningFlowFilter] = useState('all');
  const [expandedStackTraces, setExpandedStackTraces] = useState(new Set());
  const [selectedForRetry, setSelectedForRetry] = useState(new Set());
  const [selectedForCancel, setSelectedForCancel] = useState(new Set());
  const [selectedInstances, setSelectedInstances] = useState(new Set());
  const [showDiagram, setShowDiagram] = useState(false);
  const [showStackTrace, setShowStackTrace] = useState(false);
  const [expandedHistoryErrors, setExpandedHistoryErrors] = useState(new Set());
  const [mermaidLoaded, setMermaidLoaded] = useState(false);
  const [longRunningThresholdHours, setLongRunningThresholdHours] = useState(1);
  const [showChangeStageModal, setShowChangeStageModal] = useState(false);
  const [changeStageTargetInstances, setChangeStageTargetInstances] = useState([]);
  const [newStage, setNewStage] = useState('');

  // Load mermaid.js dynamically
  useEffect(() => {
    // Check if already loaded
    if (window.mermaid) {
      setMermaidLoaded(true);
      return;
    }

    // Load script
    const script = document.createElement('script');
    script.src = 'https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js';
    script.async = true;
    script.onload = () => {
      setMermaidLoaded(true);
    };
    script.onerror = () => {
      console.error('Failed to load Mermaid.js');
    };
    document.body.appendChild(script);

    return () => {
      // Cleanup on unmount
      if (script.parentNode) {
        script.parentNode.removeChild(script);
      }
    };
  }, []);

  // Initialize mermaid
  useEffect(() => {
    if (!mermaidLoaded) return;
    
    const initMermaid = () => {
      if (window.mermaid) {
        try {
          window.mermaid.initialize({ 
            startOnLoad: false,
            theme: 'dark',
            themeVariables: {
              primaryColor: '#3b82f6',
              primaryTextColor: '#e4e4e7',
              primaryBorderColor: '#3b82f6',
              lineColor: '#71717a',
              secondaryColor: '#10b981',
              tertiaryColor: '#f59e0b',
              background: '#18181b',
              mainBkg: '#27272a',
              secondBkg: '#3f3f46',
              textColor: '#e4e4e7',
              fontSize: '12px'
            }
          });
        } catch (error) {
          console.error('Mermaid initialization error:', error);
        }
      }
    };
    
    initMermaid();
  }, [mermaidLoaded]);

  // Mermaid diagram component
  const MermaidDiagram = ({ diagram, id }) => {
    const containerRef = useRef(null);
    const [error, setError] = useState(false);
    
    useEffect(() => {
      if (!mermaidLoaded || !containerRef.current || !diagram) return;
      
      const renderDiagram = async () => {
        if (!window.mermaid) {
          setError(true);
          return;
        }
        
        try {
          // Clear previous content
          containerRef.current.innerHTML = '';
          
          // Create a unique ID for this diagram
          const diagramId = `mermaid-${id}-${Date.now()}`;
          
          // Render the diagram
          const { svg } = await window.mermaid.render(diagramId, diagram);
          containerRef.current.innerHTML = svg;
          setError(false);
        } catch (err) {
          console.error('Mermaid rendering error:', err);
          setError(true);
          containerRef.current.innerHTML = `<pre class="text-xs text-zinc-400 whitespace-pre-wrap">${diagram}</pre>`;
        }
      };
      
      renderDiagram();
    }, [diagram, id, mermaidLoaded]);
    
    if (!mermaidLoaded) {
      return (
        <div className="text-xs text-zinc-400 text-center py-4">
          Loading diagram renderer...
        </div>
      );
    }
    
    if (error) {
      return (
        <div className="text-xs text-zinc-400">
          <div className="mb-2 text-amber-400">Diagram rendering error</div>
          <pre className="whitespace-pre-wrap">{diagram}</pre>
        </div>
      );
    }
    
    return <div ref={containerRef} className="mermaid-diagram" />;
  };

  useEffect(() => {
    setShowDiagram(false);
    setShowStackTrace(false);
    setExpandedHistoryErrors(new Set());
  }, [selectedInstance]);

  // Lock body scroll when modal is open
  useEffect(() => {
    if (selectedInstance || selectedFlowForDiagram) {
      document.body.style.overflow = 'hidden';
    } else {
      document.body.style.overflow = 'unset';
    }
    
    return () => {
      document.body.style.overflow = 'unset';
    };
  }, [selectedInstance, selectedFlowForDiagram]);

  const stats = useMemo(() => {
    const errorInstances = mockInstances.filter(i => i.status === 'ERROR');
    
    return {
      totalFlows: mockFlows.length,
      totalInstances: mockInstances.length,
      errorInstances: errorInstances.length,
      pendingInstances: mockInstances.filter(i => i.status === 'PENDING').length,
      runningInstances: mockInstances.filter(i => i.status === 'RUNNING').length,
      completedInstances: mockInstances.filter(i => i.status === 'COMPLETED').length,
    };
  }, [mockInstances]);

  const filteredInstances = useMemo(() => {
    return mockInstances.filter(instance => {
      const matchesSearch = instance.id.toLowerCase().includes(searchTerm.toLowerCase()) ||
                           instance.flowId.toLowerCase().includes(searchTerm.toLowerCase());
      const matchesStatus = statusFilter === 'all' || instance.status === statusFilter;
      const matchesStage = stageFilter === 'all' || instance.stage === stageFilter;
      const matchesErrorMessage = !errorMessageFilter || 
                                  (instance.errorMessage && instance.errorMessage.toLowerCase().includes(errorMessageFilter.toLowerCase()));
      const matchesIncomplete = !showIncompleteOnly || (instance.status !== 'COMPLETED' && instance.status !== 'CANCELLED');
      return matchesSearch && matchesStatus && matchesStage && matchesErrorMessage && matchesIncomplete;
    });
  }, [mockInstances, searchTerm, statusFilter, stageFilter, errorMessageFilter, showIncompleteOnly]);

  const errorsByFlowAndStage = useMemo(() => {
    const errors = mockInstances.filter(i => i.status === 'ERROR');
    
    // Apply filters
    const filteredErrors = errors.filter(instance => {
      const matchesFlow = errorFlowFilter === 'all' || instance.flowId === errorFlowFilter;
      const matchesStage = errorStageFilter === 'all' || instance.stage.toLowerCase().includes(errorStageFilter.toLowerCase());
      const matchesErrorMessage = !errorMessageFilterErrors || 
                                  (instance.errorMessage && instance.errorMessage.toLowerCase().includes(errorMessageFilterErrors.toLowerCase()));
      return matchesFlow && matchesStage && matchesErrorMessage;
    });
    
    const grouped = {};
    
    filteredErrors.forEach(instance => {
      const key = instance.flowId + '::' + instance.stage;
      if (!grouped[key]) {
        grouped[key] = {
          flowId: instance.flowId,
          stage: instance.stage,
          instances: []
        };
      }
      grouped[key].instances.push(instance);
    });
    
    return Object.values(grouped);
  }, [mockInstances, errorFlowFilter, errorStageFilter, errorMessageFilterErrors]);

  const longRunningInstances = useMemo(() => {
    const thresholdMs = longRunningThresholdHours * 60 * 60 * 1000;
    const now = Date.now();
    
    return mockInstances
      .filter(i => i.status === 'RUNNING')
      .filter(i => longRunningFlowFilter === 'all' || i.flowId === longRunningFlowFilter)
      .map(instance => ({
        ...instance,
        runningDuration: now - instance.updatedAt.getTime(),
        runningHours: (now - instance.updatedAt.getTime()) / (60 * 60 * 1000)
      }))
      .filter(instance => instance.runningDuration > thresholdMs)
      .sort((a, b) => b.runningDuration - a.runningDuration);
  }, [mockInstances, longRunningThresholdHours, longRunningFlowFilter]);

  const handleRetry = (instanceIds) => {
    console.log('Retrying instances:', instanceIds);
    alert('Retry triggered for ' + instanceIds.length + ' instance(s)');
    setSelectedInstances(new Set());
  };

  const handleCancel = (instanceIds) => {
    console.log('Cancelling instances:', instanceIds);
    alert('Cancel triggered for ' + instanceIds.length + ' instance(s)');
    setSelectedInstances(new Set());
  };

  const handleChangeStage = (instanceIds) => {
    setChangeStageTargetInstances(instanceIds);
    setShowChangeStageModal(true);
  };

  const confirmChangeStage = () => {
    console.log('Changing stage for instances:', changeStageTargetInstances, 'to:', newStage);
    alert(`Changed stage to "${newStage}" for ${changeStageTargetInstances.length} instance(s)`);
    setShowChangeStageModal(false);
    setChangeStageTargetInstances([]);
    setNewStage('');
    setSelectedInstances(new Set());
  };

  const toggleSelectInstance = (instanceId) => {
    const newSet = new Set(selectedInstances);
    if (newSet.has(instanceId)) {
      newSet.delete(instanceId);
    } else {
      newSet.add(instanceId);
    }
    setSelectedInstances(newSet);
  };

  const selectAllVisible = () => {
    const actionable = filteredInstances
      .filter(i => i.status === 'PENDING' || i.status === 'ERROR')
      .map(i => i.id);
    setSelectedInstances(new Set(actionable));
  };

  const deselectAll = () => {
    setSelectedInstances(new Set());
  };

  const selectAllInGroup = (instances) => {
    const newSet = new Set(selectedInstances);
    instances.forEach(i => newSet.add(i.id));
    setSelectedInstances(newSet);
  };

  const deselectAllInGroup = (instances) => {
    const newSet = new Set(selectedInstances);
    instances.forEach(i => newSet.delete(i.id));
    setSelectedInstances(newSet);
  };

  const toggleStackTrace = (key) => {
    const newSet = new Set(expandedStackTraces);
    if (newSet.has(key)) {
      newSet.delete(key);
    } else {
      newSet.add(key);
    }
    setExpandedStackTraces(newSet);
  };

  return (
    <div className="min-h-screen bg-zinc-950 text-zinc-100" style={{ fontFamily: '"IBM Plex Mono", monospace' }}>
      <header className="border-b border-zinc-800 bg-zinc-900/50 backdrop-blur">
        <div className="max-w-7xl mx-auto px-6 py-4">
          <div>
            <h1 className="text-2xl font-bold tracking-tight text-zinc-50">FlowLite Cockpit</h1>
            <p className="text-sm text-zinc-500 mt-1">Workflow Engine Monitoring & Management</p>
          </div>
        </div>
      </header>

      <div className="max-w-7xl mx-auto px-6">
        <div className="border-b border-zinc-800">
          <div className="flex gap-6">
            {['flows', 'errors', 'long-running', 'instances'].map(view => (
              <button
                key={view}
                onClick={() => setActiveView(view)}
                className={'px-1 py-3 text-sm font-medium border-b-2 transition-colors ' + (activeView === view ? 'border-emerald-500 text-emerald-400' : 'border-transparent text-zinc-500 hover:text-zinc-300')}
              >
                {view === 'long-running' ? 'Long Running' : view.charAt(0).toUpperCase() + view.slice(1)}
              </button>
            ))}
          </div>
        </div>
      </div>

      <div className="max-w-7xl mx-auto px-6 py-6">
        {activeView === 'flows' && (
          <div className="space-y-4">
            <h2 className="text-xl font-bold text-zinc-50 mb-4">Flow Definitions</h2>
            {mockFlows.map(flow => {
              const incompleteInstances = mockInstances.filter(i => i.flowId === flow.id && i.status !== 'COMPLETED' && i.status !== 'CANCELLED');
              
              // Calculate long-running instances for this flow
              const thresholdMs = longRunningThresholdHours * 60 * 60 * 1000;
              const now = Date.now();
              const longRunningForFlow = mockInstances.filter(i => 
                i.flowId === flow.id && 
                i.status === 'RUNNING' && 
                (now - i.updatedAt.getTime()) > thresholdMs
              ).length;
              
              const stageBreakdown = {};
              incompleteInstances.forEach(instance => {
                // Skip instances with no stage (shouldn't happen for incomplete, but defensive)
                if (!instance.stage) return;
                
                if (!stageBreakdown[instance.stage]) {
                  stageBreakdown[instance.stage] = {
                    total: 0,
                    errors: 0,
                    instances: []
                  };
                }
                stageBreakdown[instance.stage].total++;
                if (instance.status === 'ERROR') {
                  stageBreakdown[instance.stage].errors++;
                }
                stageBreakdown[instance.stage].instances.push(instance);
              });
              
              return (
                <div key={flow.id} className="bg-zinc-900 border border-zinc-800 rounded-lg p-6">
                  <div className="flex items-start justify-between mb-4">
                    <div>
                      <h3 className="text-lg font-bold text-zinc-50 font-mono">{flow.id}</h3>
                      <p className="text-xs text-zinc-500 mt-1">{flow.stages.length} stages</p>
                    </div>
                    <div className="flex gap-2">
                      <button
                        onClick={() => setSelectedFlowForDiagram(flow)}
                        className="px-3 py-1 bg-zinc-800 hover:bg-zinc-700 rounded text-xs transition-colors"
                      >
                        View Diagram
                      </button>
                      {longRunningForFlow > 0 && (
                        <button
                          onClick={() => {
                            setActiveView('long-running');
                            setLongRunningFlowFilter(flow.id);
                          }}
                          className="px-3 py-1 bg-red-500/20 hover:bg-red-500/30 text-red-400 rounded text-xs font-mono transition-colors"
                        >
                          {longRunningForFlow} long running ⚠
                        </button>
                      )}
                      <button
                        onClick={() => {
                          setActiveView('instances');
                          setSearchTerm(flow.id);
                          setStatusFilter('all');
                          setStageFilter('all');
                          setErrorMessageFilter('');
                          setShowIncompleteOnly(true);
                        }}
                        className="px-3 py-1 bg-amber-500/20 hover:bg-amber-500/30 text-amber-400 rounded text-xs font-mono transition-colors"
                      >
                        {incompleteInstances.length} incomplete →
                      </button>
                    </div>
                  </div>
                  
                  {Object.keys(stageBreakdown).length > 0 && (
                    <div className="mb-4">
                      <h4 className="text-xs font-bold text-zinc-500 uppercase tracking-wide mb-2">Active Stages</h4>
                      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-2">
                        {Object.entries(stageBreakdown).map(([stage, data]) => (
                          <button
                            key={stage}
                            onClick={() => {
                              setActiveView('instances');
                              setSearchTerm(flow.id);
                              setStatusFilter('all');
                              setStageFilter(stage);
                            }}
                            className="bg-zinc-800/50 hover:bg-zinc-800 rounded p-3 text-left transition-colors group"
                          >
                            <div className="text-xs text-zinc-400 mb-1 truncate group-hover:text-zinc-300">{stage}</div>
                            <div className="flex items-center gap-2">
                              <span className="text-sm font-bold text-zinc-200">{data.total}</span>
                              {data.errors > 0 && (
                                <button
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    setActiveView('errors');
                                    setErrorFlowFilter(flow.id);
                                    setErrorStageFilter(stage);
                                  }}
                                  className="text-xs px-1.5 py-0.5 bg-red-500/20 hover:bg-red-500/30 text-red-400 rounded font-mono transition-colors"
                                >
                                  {data.errors} err
                                </button>
                              )}
                            </div>
                          </button>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}

        {activeView === 'errors' && (
          <div className="space-y-4">
            <div className="flex items-center gap-4 mb-6">
              <select
                value={errorFlowFilter}
                onChange={(e) => setErrorFlowFilter(e.target.value)}
                className="bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
              >
                <option value="all">All Flows</option>
                {mockFlows.map(flow => (
                  <option key={flow.id} value={flow.id}>{flow.id}</option>
                ))}
              </select>
              <input
                type="text"
                placeholder="Filter by stage..."
                value={errorStageFilter === 'all' ? '' : errorStageFilter}
                onChange={(e) => setErrorStageFilter(e.target.value || 'all')}
                className="flex-1 bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
              />
              <input
                type="text"
                placeholder="Filter by error message..."
                value={errorMessageFilterErrors}
                onChange={(e) => setErrorMessageFilterErrors(e.target.value)}
                className="flex-1 bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
              />
              {(errorFlowFilter !== 'all' || errorStageFilter !== 'all' || errorMessageFilterErrors) && (
                <button
                  onClick={() => {
                    setErrorFlowFilter('all');
                    setErrorStageFilter('all');
                    setErrorMessageFilterErrors('');
                  }}
                  className="px-3 py-2 bg-zinc-800 hover:bg-zinc-700 rounded-lg text-sm transition-colors whitespace-nowrap"
                >
                  Clear Filters
                </button>
              )}
            </div>

            {errorFlowFilter === 'all' && errorStageFilter === 'all' && !errorMessageFilterErrors ? (
              <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-12 text-center">
                <div className="text-zinc-400 mb-4">
                  <AlertCircle size={48} className="mx-auto mb-4 opacity-50" />
                  <p className="text-lg font-medium mb-2">Apply filters to view errors</p>
                  <p className="text-sm text-zinc-500">Select a flow or filter by stage/message to view error instances</p>
                  <p className="text-xs text-zinc-600 mt-2">This prevents overwhelming the UI with potentially 1000s of errors</p>
                </div>
              </div>
            ) : (
              <>
                {selectedInstances.size > 0 && (
                  <div className="flex items-center justify-between p-4 bg-emerald-500/10 border border-emerald-500/30 rounded-lg mb-4">
                    <span className="text-sm text-emerald-400">
                      {selectedInstances.size} error(s) selected
                    </span>
                    <div className="flex gap-2">
                      <button
                        onClick={() => handleRetry(Array.from(selectedInstances))}
                        className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 rounded transition-colors text-sm font-medium flex items-center gap-2"
                      >
                        <RefreshCw size={14} />
                        Retry Selected ({selectedInstances.size})
                      </button>
                      <button
                        onClick={() => handleChangeStage(Array.from(selectedInstances))}
                        className="px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded transition-colors text-sm font-medium flex items-center gap-2"
                      >
                        <ChevronRight size={14} />
                        Change Stage ({selectedInstances.size})
                      </button>
                      <button
                        onClick={() => handleCancel(Array.from(selectedInstances))}
                        className="px-4 py-2 bg-red-600 hover:bg-red-700 rounded transition-colors text-sm font-medium flex items-center gap-2"
                      >
                        <X size={14} />
                        Cancel Selected ({selectedInstances.size})
                      </button>
                    </div>
                  </div>
                )}
                
                {errorsByFlowAndStage.length === 0 ? (
                  <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-12 text-center">
                    <CheckCircle size={48} className="mx-auto mb-4 text-emerald-500 opacity-50" />
                    <p className="text-lg font-medium text-zinc-300 mb-2">No errors match filters</p>
                    <p className="text-sm text-zinc-500">Try adjusting your filter criteria</p>
                  </div>
                ) : (
                  errorsByFlowAndStage.map((group, idx) => (
              <div key={idx} className="bg-zinc-900 border border-red-900/30 rounded-lg p-6">
                <div className="flex items-start justify-between mb-4">
                  <div>
                    <h3 className="text-lg font-bold text-zinc-50 font-mono">{group.flowId}</h3>
                    <p className="text-sm text-zinc-500 mt-1">Stage: <span className="font-mono">{group.stage}</span></p>
                  </div>
                  <div className="flex items-center gap-2">
                    <button
                      onClick={() => selectAllInGroup(group.instances)}
                      className="px-3 py-1 bg-zinc-700 hover:bg-zinc-600 rounded text-xs transition-colors"
                    >
                      Select All
                    </button>
                    <button
                      onClick={() => deselectAllInGroup(group.instances)}
                      className="px-3 py-1 bg-zinc-700 hover:bg-zinc-600 rounded text-xs transition-colors"
                    >
                      Deselect All
                    </button>
                    <div className="px-3 py-1 bg-red-500/20 text-red-400 rounded text-xs font-mono">
                      {group.instances.length} errors
                    </div>
                  </div>
                </div>
                
                <div className="space-y-2 mt-4">
                  {group.instances.map(instance => {
                    const stackTraceKey = instance.id + '-stack';
                    const isStackTraceExpanded = expandedStackTraces.has(stackTraceKey);
                    
                    return (
                      <div 
                        key={instance.id} 
                        className="bg-zinc-800/50 rounded p-3 hover:bg-zinc-800 transition-colors cursor-pointer"
                        onClick={() => setSelectedInstance(instance)}
                      >
                        <div className="flex items-start gap-3">
                          <input
                            type="checkbox"
                            checked={selectedInstances.has(instance.id)}
                            onChange={() => toggleSelectInstance(instance.id)}
                            onClick={(e) => e.stopPropagation()}
                            className="w-4 h-4 rounded border-zinc-600 bg-zinc-700 mt-1 flex-shrink-0"
                          />
                          <div className="flex-1 min-w-0">
                            <div className="text-sm font-mono text-zinc-300">{instance.id}</div>
                            <div className="text-xs text-red-400 mt-1">{instance.errorMessage}</div>
                            <div className="flex flex-wrap gap-2 mt-2">
                              {instance.autoRetry && (
                                <span className="px-1.5 py-0.5 bg-emerald-500/20 text-emerald-400 rounded text-xs font-mono">
                                  Auto-Retry
                                </span>
                              )}
                              {instance.userRetriable && (
                                <span className="px-1.5 py-0.5 bg-blue-500/20 text-blue-400 rounded text-xs font-mono">
                                  User Retriable
                                </span>
                              )}
                              {instance.retryCount > 0 && (
                                <span className="text-xs text-zinc-500">Retries: {instance.retryCount}</span>
                              )}
                            </div>
                            
                            {instance.stackTrace && (
                              <div className="mt-2">
                                <button
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    toggleStackTrace(stackTraceKey);
                                  }}
                                  className="flex items-center gap-2 text-xs text-zinc-400 hover:text-zinc-300 transition-colors"
                                >
                                  <ChevronRight size={12} className={'transition-transform ' + (isStackTraceExpanded ? 'rotate-90' : '')} />
                                  Stack Trace
                                </button>
                                {isStackTraceExpanded && (
                                  <pre className="bg-zinc-900/50 rounded p-2 text-xs text-zinc-400 overflow-x-auto font-mono mt-2">
                                    {instance.stackTrace}
                                  </pre>
                                )}
                              </div>
                            )}
                          </div>
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            )))}
          </>
        )}
          </div>
        )}

        {activeView === 'long-running' && (
          <div className="space-y-4">
            <div className="flex items-center justify-between mb-6">
              <div>
                <h2 className="text-xl font-bold text-zinc-50">Long Running Instances</h2>
                <p className="text-sm text-zinc-500 mt-1">Instances in RUNNING state longer than threshold</p>
              </div>
              <div className="flex items-center gap-3">
                <select
                  value={longRunningFlowFilter}
                  onChange={(e) => setLongRunningFlowFilter(e.target.value)}
                  className="bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
                >
                  <option value="all">All Flows</option>
                  {mockFlows.map(flow => (
                    <option key={flow.id} value={flow.id}>{flow.id}</option>
                  ))}
                </select>
                <label className="text-sm text-zinc-400">Threshold:</label>
                <input
                  type="number"
                  min="0.1"
                  step="0.5"
                  value={longRunningThresholdHours}
                  onChange={(e) => setLongRunningThresholdHours(parseFloat(e.target.value) || 1)}
                  className="w-20 bg-zinc-900 border border-zinc-800 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-emerald-500"
                />
                <span className="text-sm text-zinc-400">hours</span>
                {longRunningFlowFilter !== 'all' && (
                  <button
                    onClick={() => setLongRunningFlowFilter('all')}
                    className="px-3 py-2 bg-zinc-800 hover:bg-zinc-700 rounded-lg text-sm transition-colors whitespace-nowrap"
                  >
                    Clear Filter
                  </button>
                )}
              </div>
            </div>

            {longRunningInstances.length === 0 ? (
              <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-12 text-center">
                <CheckCircle size={48} className="mx-auto mb-4 text-emerald-500 opacity-50" />
                <p className="text-lg font-medium text-zinc-300 mb-2">No long running instances</p>
                <p className="text-sm text-zinc-500">All RUNNING instances are within the {longRunningThresholdHours}h threshold</p>
              </div>
            ) : (
              <>
                <div className="flex items-center justify-between mb-4">
                  <div className="text-sm text-zinc-400">
                    {longRunningInstances.length} instance(s) running longer than {longRunningThresholdHours}h
                  </div>
                  <div className="flex items-center gap-2">
                    <button
                      onClick={() => {
                        const ids = longRunningInstances.map(i => i.id);
                        setSelectedInstances(new Set(ids));
                      }}
                      className="px-3 py-2 bg-zinc-800 hover:bg-zinc-700 rounded-lg text-sm transition-colors"
                    >
                      Select All ({longRunningInstances.length})
                    </button>
                    {selectedInstances.size > 0 && (
                      <button
                        onClick={deselectAll}
                        className="px-3 py-2 bg-zinc-800 hover:bg-zinc-700 rounded-lg text-sm transition-colors"
                      >
                        Deselect All
                      </button>
                    )}
                  </div>
                </div>

                {selectedInstances.size > 0 && (
                  <div className="flex items-center justify-between p-4 bg-emerald-500/10 border border-emerald-500/30 rounded-lg mb-4">
                    <span className="text-sm text-emerald-400">
                      {selectedInstances.size} instance(s) selected
                    </span>
                    <button
                      onClick={() => handleRetry(Array.from(selectedInstances))}
                      className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 rounded transition-colors text-sm font-medium flex items-center gap-2"
                    >
                      <RefreshCw size={14} />
                      Retry Selected ({selectedInstances.size})
                    </button>
                  </div>
                )}

                <div className="bg-zinc-900 border border-zinc-800 rounded-lg overflow-hidden">
                  <table className="w-full text-sm">
                    <thead className="bg-zinc-800/50">
                      <tr className="text-left text-zinc-400">
                        <th className="px-4 py-3 font-medium w-12"></th>
                        <th className="px-4 py-3 font-medium">Instance ID</th>
                        <th className="px-4 py-3 font-medium">Flow</th>
                        <th className="px-4 py-3 font-medium">Stage</th>
                        <th className="px-4 py-3 font-medium">Running Duration</th>
                        <th className="px-4 py-3 font-medium">Last Updated</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-zinc-800">
                      {longRunningInstances.map(instance => {
                        const hours = Math.floor(instance.runningHours);
                        const minutes = Math.floor((instance.runningHours - hours) * 60);
                        const durationText = hours > 0 ? `${hours}h ${minutes}m` : `${minutes}m`;
                        const isVeryLong = instance.runningHours > longRunningThresholdHours * 2;
                        
                        return (
                          <tr 
                            key={instance.id} 
                            className="hover:bg-zinc-800/30 transition-colors cursor-pointer"
                            onClick={() => setSelectedInstance(instance)}
                          >
                            <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
                              <input
                                type="checkbox"
                                checked={selectedInstances.has(instance.id)}
                                onChange={() => toggleSelectInstance(instance.id)}
                                className="w-4 h-4 rounded border-zinc-600 bg-zinc-700"
                              />
                            </td>
                            <td className="px-4 py-3 font-mono text-xs text-zinc-300">{instance.id}</td>
                            <td className="px-4 py-3 font-mono text-xs text-zinc-300">{instance.flowId}</td>
                            <td className="px-4 py-3 font-mono text-xs text-zinc-400">{instance.stage}</td>
                            <td className="px-4 py-3">
                              <span className={'px-2 py-1 rounded text-xs font-mono ' + (isVeryLong ? 'bg-red-500/20 text-red-400' : 'bg-amber-500/20 text-amber-400')}>
                                {durationText}
                              </span>
                            </td>
                            <td className="px-4 py-3 text-xs text-zinc-500">
                              {instance.updatedAt.toLocaleString()}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </div>
        )}

        {activeView === 'instances' && (
          <div className="space-y-4">
            <div className="flex items-center gap-4 mb-6">
              <div className="flex-1 relative">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-zinc-500" size={16} />
                <input
                  type="text"
                  placeholder="Search by instance ID or flow ID..."
                  value={searchTerm}
                  onChange={(e) => setSearchTerm(e.target.value)}
                  className="w-full bg-zinc-900 border border-zinc-800 rounded-lg pl-10 pr-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
                />
              </div>
              <select
                value={statusFilter}
                onChange={(e) => {
                  setStatusFilter(e.target.value);
                  if (e.target.value !== 'all') setShowIncompleteOnly(false);
                }}
                className="bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
              >
                <option value="all">All Status</option>
                <option value="PENDING">Pending</option>
                <option value="RUNNING">Running</option>
                <option value="ERROR">Error</option>
                <option value="COMPLETED">Completed</option>
                <option value="CANCELLED">Cancelled</option>
              </select>
            </div>

            <div className="flex items-center gap-4 mb-4">
              <input
                type="text"
                placeholder="Filter by stage..."
                value={stageFilter === 'all' ? '' : stageFilter}
                onChange={(e) => setStageFilter(e.target.value || 'all')}
                className="flex-1 bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
              />
              <input
                type="text"
                placeholder="Filter by error message..."
                value={errorMessageFilter}
                onChange={(e) => setErrorMessageFilter(e.target.value)}
                className="flex-1 bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
              />
              {showIncompleteOnly && (
                <div className="flex items-center gap-2 px-3 py-2 bg-amber-500/20 text-amber-400 rounded-lg text-sm">
                  <span>Incomplete Only</span>
                  <button
                    onClick={() => setShowIncompleteOnly(false)}
                    className="hover:bg-amber-500/30 rounded p-0.5 transition-colors"
                  >
                    <X size={14} />
                  </button>
                </div>
              )}
              {(searchTerm || statusFilter !== 'all' || stageFilter !== 'all' || errorMessageFilter) && (
                <button
                  onClick={() => {
                    setSearchTerm('');
                    setStatusFilter('all');
                    setStageFilter('all');
                    setErrorMessageFilter('');
                    setShowIncompleteOnly(false);
                  }}
                  className="px-3 py-2 bg-zinc-800 hover:bg-zinc-700 rounded-lg text-sm transition-colors whitespace-nowrap"
                >
                  Clear Filters
                </button>
              )}
            </div>

            {(searchTerm || statusFilter !== 'all' || stageFilter !== 'all' || errorMessageFilter || showIncompleteOnly) && (
              <>
                <div className="flex items-center justify-between mb-4">
                  <div className="flex items-center gap-2">
                    <button
                      onClick={selectAllVisible}
                      className="px-3 py-2 bg-zinc-800 hover:bg-zinc-700 rounded-lg text-sm transition-colors"
                    >
                      Select All Actionable ({filteredInstances.filter(i => i.status === 'PENDING' || i.status === 'ERROR').length})
                    </button>
                    {selectedInstances.size > 0 && (
                      <button
                        onClick={deselectAll}
                        className="px-3 py-2 bg-zinc-800 hover:bg-zinc-700 rounded-lg text-sm transition-colors"
                      >
                        Deselect All
                      </button>
                    )}
                  </div>
                  <div className="text-sm text-zinc-500">
                    {filteredInstances.length} instances
                  </div>
                </div>

                {filteredInstances.length > 500 && (
                  <div className="bg-amber-500/10 border border-amber-500/30 rounded-lg p-3 mb-4">
                    <p className="text-sm text-amber-400">
                      ⚠️ Showing {filteredInstances.length} instances. Consider adding more specific filters (stage, error message) for better performance.
                    </p>
                  </div>
                )}

                {selectedInstances.size > 0 && (
                  <div className="flex items-center justify-between p-4 bg-emerald-500/10 border border-emerald-500/30 rounded-lg mb-4">
                    <span className="text-sm text-emerald-400">
                      {selectedInstances.size} instance(s) selected
                    </span>
                    <div className="flex gap-2">
                      <button
                        onClick={() => handleRetry(Array.from(selectedInstances))}
                        className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 rounded transition-colors text-sm font-medium flex items-center gap-2"
                      >
                        <RefreshCw size={14} />
                        Retry Selected ({selectedInstances.size})
                      </button>
                      <button
                        onClick={() => handleChangeStage(Array.from(selectedInstances))}
                        className="px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded transition-colors text-sm font-medium flex items-center gap-2"
                      >
                        <ChevronRight size={14} />
                        Change Stage ({selectedInstances.size})
                      </button>
                      <button
                        onClick={() => handleCancel(Array.from(selectedInstances))}
                        className="px-4 py-2 bg-red-600 hover:bg-red-700 rounded transition-colors text-sm font-medium flex items-center gap-2"
                      >
                        <X size={14} />
                        Cancel Selected ({selectedInstances.size})
                      </button>
                    </div>
                  </div>
                )}
              </>
            )}

            {!searchTerm && statusFilter === 'all' && stageFilter === 'all' && !errorMessageFilter && !showIncompleteOnly ? (
              <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-12 text-center">
                <div className="text-zinc-400 mb-4">
                  <Database size={48} className="mx-auto mb-4 opacity-50" />
                  <p className="text-lg font-medium mb-2">Apply filters to view instances</p>
                  <p className="text-sm text-zinc-500">Use the filters above to search by flow, status, stage, or error message</p>
                </div>
              </div>
            ) : (
              <div className="bg-zinc-900 border border-zinc-800 rounded-lg overflow-hidden">
                <table className="w-full text-sm">
                  <thead className="bg-zinc-800/50">
                    <tr className="text-left text-zinc-400">
                      <th className="px-4 py-3 font-medium w-12"></th>
                      <th className="px-4 py-3 font-medium">Instance ID</th>
                      <th className="px-4 py-3 font-medium">Flow</th>
                      <th className="px-4 py-3 font-medium">Stage</th>
                      <th className="px-4 py-3 font-medium">Status</th>
                      <th className="px-4 py-3 font-medium">Updated</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-zinc-800">
                    {filteredInstances.map(instance => {
                      const isActionable = instance.status === 'PENDING' || instance.status === 'ERROR';
                      
                      return (
                        <tr 
                          key={instance.id} 
                          className="hover:bg-zinc-800/30 transition-colors cursor-pointer"
                          onClick={() => setSelectedInstance(instance)}
                        >
                          <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
                            {isActionable && (
                              <input
                                type="checkbox"
                                checked={selectedInstances.has(instance.id)}
                                onChange={() => toggleSelectInstance(instance.id)}
                                className="w-4 h-4 rounded border-zinc-600 bg-zinc-700"
                              />
                            )}
                          </td>
                          <td className="px-4 py-3 font-mono text-xs text-zinc-300">{instance.id}</td>
                          <td className="px-4 py-3 font-mono text-xs text-zinc-300">{instance.flowId}</td>
                          <td className="px-4 py-3 font-mono text-xs text-zinc-400">{instance.stage || '—'}</td>
                          <td className="px-4 py-3">
                            <StatusBadge status={instance.status} />
                          </td>
                          <td className="px-4 py-3 text-xs text-zinc-500">
                            {instance.updatedAt.toLocaleString()}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}
      </div>

      {selectedInstance && (
        <div 
          className="fixed inset-0 bg-black/80 backdrop-blur-sm flex items-center justify-center z-50 p-4 overflow-hidden" 
          onClick={() => setSelectedInstance(null)}
        >
          <div 
            className="bg-zinc-900 border border-zinc-800 rounded-lg w-full max-w-4xl flex flex-col" 
            style={{ maxHeight: 'calc(100vh - 2rem)' }}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="bg-zinc-900 border-b border-zinc-800 p-6 flex items-center justify-between flex-shrink-0">
              <div className="flex-1">
                <h3 className="text-lg font-bold text-zinc-50">Instance Details</h3>
                <p className="text-sm text-zinc-500 font-mono mt-1">{selectedInstance.id}</p>
              </div>
              <div className="flex items-center gap-2">
                {selectedInstance.status === 'ERROR' && (
                  <button
                    onClick={() => {
                      handleRetry([selectedInstance.id]);
                      setSelectedInstance(null);
                    }}
                    className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 rounded transition-colors text-sm font-medium flex items-center gap-2"
                  >
                    <RefreshCw size={14} />
                    Retry
                  </button>
                )}
                {(selectedInstance.status === 'PENDING' || selectedInstance.status === 'ERROR') && (
                  <button
                    onClick={() => {
                      handleChangeStage([selectedInstance.id]);
                      setSelectedInstance(null);
                    }}
                    className="px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded transition-colors text-sm font-medium flex items-center gap-2"
                  >
                    <ChevronRight size={14} />
                    Change Stage
                  </button>
                )}
                {(selectedInstance.status === 'PENDING' || selectedInstance.status === 'ERROR') && (
                  <button
                    onClick={() => {
                      handleCancel([selectedInstance.id]);
                      setSelectedInstance(null);
                    }}
                    className="px-4 py-2 bg-red-600 hover:bg-red-700 rounded transition-colors text-sm font-medium flex items-center gap-2"
                  >
                    <X size={14} />
                    Cancel
                  </button>
                )}
                <button
                  onClick={() => setSelectedInstance(null)}
                  className="p-2 hover:bg-zinc-800 rounded transition-colors"
                >
                  <X size={20} />
                </button>
              </div>
            </div>
            
            <div className="p-6 space-y-6 overflow-y-auto flex-1 min-h-0">
              <div>
                <h4 className="text-sm font-bold text-zinc-400 uppercase tracking-wide mb-3">Overview</h4>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <div className="text-xs text-zinc-500 mb-1">Flow ID</div>
                    <div className="text-sm font-mono text-zinc-300">{selectedInstance.flowId}</div>
                  </div>
                  <div>
                    <div className="text-xs text-zinc-500 mb-1">Current Stage</div>
                    <div className="text-sm font-mono text-zinc-300">{selectedInstance.stage || '—'}</div>
                  </div>
                  <div>
                    <div className="text-xs text-zinc-500 mb-1">Status</div>
                    <StatusBadge status={selectedInstance.status} />
                  </div>
                  <div>
                    <div className="text-xs text-zinc-500 mb-1">Created At</div>
                    <div className="text-sm text-zinc-300">{selectedInstance.createdAt.toLocaleString()}</div>
                  </div>
                </div>
              </div>

              <div>
                <button
                  onClick={() => setShowDiagram(!showDiagram)}
                  className="flex items-center gap-2 text-sm font-bold text-zinc-400 uppercase tracking-wide mb-3 hover:text-zinc-300 transition-colors"
                >
                  <ChevronRight size={16} className={'transition-transform ' + (showDiagram ? 'rotate-90' : '')} />
                  Flow Diagram
                </button>
                {showDiagram && (
                  <>
                    <div className="bg-zinc-800/50 rounded-lg p-6 overflow-auto max-h-96">
                      <MermaidDiagram 
                        diagram={mockFlows.find(f => f.id === selectedInstance.flowId)?.diagram || 'graph TD\nA[Diagram not available]'} 
                        id={`instance-${selectedInstance.id}`}
                      />
                    </div>
                    {selectedInstance.stage && (
                      <p className="text-xs text-zinc-500 mt-2">
                        Current stage: <span className="font-mono text-emerald-400">{selectedInstance.stage}</span>
                      </p>
                    )}
                  </>
                )}
              </div>

              {selectedInstance.status === 'ERROR' && (
                <div>
                  <h4 className="text-sm font-bold text-zinc-400 uppercase tracking-wide mb-3">Error Information</h4>
                  <div className="bg-red-500/10 border border-red-500/30 rounded-lg p-4 space-y-3">
                    <div className="flex flex-wrap gap-2 mb-3">
                      {selectedInstance.autoRetry && (
                        <span className="px-2 py-1 bg-emerald-500/20 text-emerald-400 rounded text-xs font-mono">
                          Auto-Retry
                        </span>
                      )}
                      {selectedInstance.userRetriable && (
                        <span className="px-2 py-1 bg-blue-500/20 text-blue-400 rounded text-xs font-mono">
                          User Retriable
                        </span>
                      )}
                    </div>
                    <div>
                      <div className="text-xs text-zinc-500 mb-2">Error Message</div>
                      <div className="text-sm text-zinc-300">{selectedInstance.errorMessage}</div>
                    </div>
                    {selectedInstance.retryCount > 0 && (
                      <div>
                        <div className="text-xs text-zinc-500 mb-2">Retry Count</div>
                        <div className="text-sm text-zinc-300">{selectedInstance.retryCount} attempt(s)</div>
                      </div>
                    )}
                    {selectedInstance.stackTrace && (
                      <div>
                        <button
                          onClick={() => setShowStackTrace(!showStackTrace)}
                          className="flex items-center gap-2 text-xs text-zinc-400 hover:text-zinc-300 transition-colors mb-2"
                        >
                          <ChevronRight size={14} className={'transition-transform ' + (showStackTrace ? 'rotate-90' : '')} />
                          Stack Trace
                        </button>
                        {showStackTrace && (
                          <pre className="bg-zinc-900/50 rounded p-3 text-xs text-zinc-400 overflow-x-auto font-mono">
                            {selectedInstance.stackTrace}
                          </pre>
                        )}
                      </div>
                    )}
                  </div>
                </div>
              )}

              <div>
                <h4 className="text-sm font-bold text-zinc-400 uppercase tracking-wide mb-3">Event History</h4>
                <div className="space-y-3">
                  {generateHistory(selectedInstance.id).map((event, idx) => {
                    const errorKey = selectedInstance.id + '-' + idx;
                    const isExpanded = expandedHistoryErrors.has(errorKey);
                    const isErrorEvent = event.event === 'ERROR_OCCURRED';
                    const isRetryEvent = event.event === 'RETRY_ATTEMPTED';
                    
                    return (
                      <div key={idx} className="flex gap-4">
                        <div className="text-xs text-zinc-500 w-32 flex-shrink-0">
                          {event.timestamp.toLocaleTimeString()}
                        </div>
                        <div className="flex-1">
                          <div className={'text-sm font-mono ' + (isErrorEvent ? 'text-red-400' : isRetryEvent ? 'text-amber-400' : 'text-zinc-300')}>
                            {event.event}
                          </div>
                          <div className="text-xs text-zinc-500 mt-1">{event.stage}</div>
                          {isErrorEvent && event.details && (
                            <div className="mt-2 bg-red-500/10 border border-red-500/30 rounded p-3 space-y-2">
                              <div className="text-xs text-red-400">{event.details}</div>
                              {event.stackTrace && (
                                <div>
                                  <button
                                    onClick={() => {
                                      const newSet = new Set(expandedHistoryErrors);
                                      if (isExpanded) {
                                        newSet.delete(errorKey);
                                      } else {
                                        newSet.add(errorKey);
                                      }
                                      setExpandedHistoryErrors(newSet);
                                    }}
                                    className="flex items-center gap-2 text-xs text-zinc-400 hover:text-zinc-300 transition-colors"
                                  >
                                    <ChevronRight size={12} className={'transition-transform ' + (isExpanded ? 'rotate-90' : '')} />
                                    Stack Trace
                                  </button>
                                  {isExpanded && (
                                    <pre className="bg-zinc-900/50 rounded p-2 text-xs text-zinc-400 overflow-x-auto font-mono mt-2">
                                      {event.stackTrace}
                                    </pre>
                                  )}
                                </div>
                              )}
                            </div>
                          )}
                          {isRetryEvent && event.details && (
                            <div className="mt-2 bg-amber-500/10 border border-amber-500/30 rounded p-2">
                              <div className="text-xs text-amber-400">{event.details}</div>
                            </div>
                          )}
                          {event.details && !isErrorEvent && !isRetryEvent && (
                            <div className="text-xs text-zinc-500 mt-1">{event.details}</div>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {selectedFlowForDiagram && (
        <div 
          className="fixed inset-0 bg-black/80 backdrop-blur-sm flex items-center justify-center z-50 p-4 overflow-hidden" 
          onClick={() => setSelectedFlowForDiagram(null)}
        >
          <div 
            className="bg-zinc-900 border border-zinc-800 rounded-lg w-full max-w-5xl flex flex-col"
            style={{ maxHeight: 'calc(100vh - 2rem)' }}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="bg-zinc-900 border-b border-zinc-800 p-6 flex items-center justify-between flex-shrink-0">
              <div>
                <h3 className="text-lg font-bold text-zinc-50 font-mono">{selectedFlowForDiagram.id}</h3>
                <p className="text-sm text-zinc-500 mt-1">Flow Diagram</p>
              </div>
              <button
                onClick={() => setSelectedFlowForDiagram(null)}
                className="p-2 hover:bg-zinc-800 rounded transition-colors"
              >
                <X size={20} />
              </button>
            </div>
            
            <div className="p-6 overflow-y-auto flex-1 min-h-0">
              <div className="bg-zinc-800/50 rounded-lg p-8 overflow-auto">
                <MermaidDiagram 
                  diagram={selectedFlowForDiagram.diagram} 
                  id={`flow-${selectedFlowForDiagram.id}`}
                />
              </div>
            </div>
          </div>
        </div>
      )}

      {showChangeStageModal && (
        <div 
          className="fixed inset-0 bg-black/80 backdrop-blur-sm flex items-center justify-center z-50 p-4" 
          onClick={() => {
            setShowChangeStageModal(false);
            setChangeStageTargetInstances([]);
            setNewStage('');
          }}
        >
          <div 
            className="bg-zinc-900 border border-zinc-800 rounded-lg w-full max-w-md"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="bg-zinc-900 border-b border-zinc-800 p-6 flex items-center justify-between">
              <div>
                <h3 className="text-lg font-bold text-zinc-50">Change Stage</h3>
                <p className="text-sm text-zinc-500 mt-1">{changeStageTargetInstances.length} instance(s) selected</p>
              </div>
              <button
                onClick={() => {
                  setShowChangeStageModal(false);
                  setChangeStageTargetInstances([]);
                  setNewStage('');
                }}
                className="p-2 hover:bg-zinc-800 rounded transition-colors"
              >
                <X size={20} />
              </button>
            </div>
            
            <div className="p-6 space-y-4">
              <div>
                <label className="text-sm font-medium text-zinc-400 mb-2 block">
                  Select New Stage
                </label>
                <select
                  value={newStage}
                  onChange={(e) => setNewStage(e.target.value)}
                  className="w-full bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-3 text-sm focus:outline-none focus:border-blue-500"
                >
                  <option value="">Select a stage...</option>
                  {mockFlows.flatMap(flow => 
                    flow.stages.map(stage => (
                      <option key={`${flow.id}-${stage}`} value={stage}>
                        {stage} ({flow.id})
                      </option>
                    ))
                  )}
                </select>
              </div>

              <div className="bg-amber-500/10 border border-amber-500/30 rounded-lg p-3">
                <p className="text-xs text-amber-400">
                  ⚠️ Changing the stage will move the instance(s) to the selected stage. 
                  The engine will re-process from that stage on the next tick.
                </p>
              </div>

              <div className="flex gap-2 justify-end">
                <button
                  onClick={() => {
                    setShowChangeStageModal(false);
                    setChangeStageTargetInstances([]);
                    setNewStage('');
                  }}
                  className="px-4 py-2 bg-zinc-800 hover:bg-zinc-700 rounded transition-colors text-sm font-medium"
                >
                  Cancel
                </button>
                <button
                  onClick={confirmChangeStage}
                  disabled={!newStage}
                  className="px-4 py-2 bg-blue-600 hover:bg-blue-700 disabled:bg-zinc-700 disabled:text-zinc-500 disabled:cursor-not-allowed rounded transition-colors text-sm font-medium flex items-center gap-2"
                >
                  <ChevronRight size={14} />
                  Change Stage
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500;600;700&display=swap');
        
        * {
          scrollbar-width: thin;
          scrollbar-color: #3f3f46 #18181b;
        }
        
        *::-webkit-scrollbar {
          width: 8px;
          height: 8px;
        }
        
        *::-webkit-scrollbar-track {
          background: #18181b;
        }
        
        *::-webkit-scrollbar-thumb {
          background: #3f3f46;
          border-radius: 4px;
        }
        
        *::-webkit-scrollbar-thumb:hover {
          background: #52525b;
        }
        
        /* Mermaid diagram styles */
        .mermaid-diagram {
          display: flex;
          justify-content: center;
          align-items: center;
        }
        
        .mermaid-diagram svg {
          max-width: 100%;
          height: auto;
        }
      `}</style>
    </div>
  );
};

export default FlowLiteCockpit;
