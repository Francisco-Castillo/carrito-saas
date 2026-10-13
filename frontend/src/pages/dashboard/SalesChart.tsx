import { useEffect, useRef } from "react";
import Chart from "chart.js/auto";
import type { SalesByHour } from "./api";

interface SalesChartProps {
  /** null = sin datos aún: el canvas queda vacío, como el legacy antes de la respuesta. */
  data: SalesByHour[] | null;
}

/**
 * Puerto 1:1 de la configuración de Chart.js del legacy loadSalesChart()
 * (dashboard.js): tipo line, fill, tension 0.3, sin leyenda, tooltips y
 * ticks formateados en es-AR.
 */
export default function SalesChart({ data }: SalesChartProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const chartRef = useRef<Chart | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !data) return;

    const labels = data.map((d) => d.hour);
    const values = data.map((d) => d.revenue);

    // 🔥 FIX DEFINITIVO (mismo patrón del legacy): destruir el chart previo.
    const existingChart = chartRef.current;
    if (existingChart) {
      existingChart.destroy();
    }

    chartRef.current = new Chart(canvas, {
      type: "line",
      data: {
        labels,
        datasets: [
          {
            label: "Ventas",
            data: values,
            borderColor: "#6366f1",
            backgroundColor: "rgba(99,102,241,0.2)",
            fill: true,
            tension: 0.3,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              label: function (context) {
                return (
                  "Ventas: $ " +
                  new Intl.NumberFormat("es-AR").format(context.parsed.y ?? 0)
                );
              },
            },
          },
        },
        scales: {
          x: {
            grid: { display: false },
          },
          y: {
            ticks: {
              callback: (value) =>
                "$ " + new Intl.NumberFormat("es-AR").format(Number(value)),
            },
            grid: { color: "rgba(255,255,255,0.05)" },
          },
        },
      },
    });

    return () => {
      chartRef.current?.destroy();
      chartRef.current = null;
    };
  }, [data]);

  return (
    <section className="chart-section panel glass-panel fade-up">
      <h2>Ventas hoy</h2>

      <div className="chart-container">
        <canvas ref={canvasRef} />
      </div>
    </section>
  );
}
