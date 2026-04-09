import { useEffect, useRef, useState } from 'react';

interface MermaidWindow extends Window {
  mermaid?: {
    initialize: (config: Record<string, unknown>) => void;
    render: (id: string, diagram: string) => Promise<{ svg: string }>;
  };
}

export const MermaidDiagram = ({
  diagram,
  id,
  mermaidLoaded,
}: {
  diagram: string;
  id: string;
  mermaidLoaded: boolean;
}) => {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (!mermaidLoaded || !containerRef.current || !diagram) return;

    const renderDiagram = async () => {
      const maybeMermaid = (window as MermaidWindow).mermaid;
      if (!maybeMermaid) return;

      try {
        containerRef.current!.innerHTML = '';
        const diagramId = `mermaid-${id}-${Date.now()}`;
        const { svg } = await maybeMermaid.render(diagramId, diagram);
        containerRef.current!.innerHTML = svg;
        setError(false);
      } catch (err) {
        console.error('Mermaid rendering error:', err);
        setError(true);
        containerRef.current!.innerHTML = `<pre class="text-xs text-zinc-400 whitespace-pre-wrap">${diagram}</pre>`;
      }
    };

    void renderDiagram();
  }, [diagram, id, mermaidLoaded]);

  if (!mermaidLoaded) return <div className="text-xs text-zinc-400 text-center py-4">Loading diagram renderer...</div>;
  if (error) return <pre className="text-xs text-zinc-400 whitespace-pre-wrap">{diagram}</pre>;

  return <div ref={containerRef} className="mermaid-diagram" />;
};