import { useEffect, useRef } from "react";
import Chart from "chart.js/auto";
import type { SalesByDay } from "./api";
import { formatDayLabel } from "./format";

interface SalesByDayChartProps {
  /** null = sin datos aún: el canvas queda vacío hasta la primera respuesta. */
  data: SalesByDay[] | null;
}

/**
 * Gráfico de barras de las ventas de los últimos 7 días (hoy incluido).
 * El backend siempre devuelve la serie completa de 7 días, con 0 en los días
 * sin ventas, así que el eje X es estable. Mismo estilo Chart.js que
 * SalesChart (línea → barras para distinguir el corte temporal).
 */
export default function SalesByDayChart({ data }: SalesByDayChartProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const chartRef = useRef<Chart | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !data) return;

    const labels = data.map((d) => formatDayLabel(d.day));
    const values = data.map((d) => d.revenue);

    chartRef.current?.destroy();

    chartRef.current = new Chart(canvas, {
      type: "bar",
      data: {
        labels,
        datasets: [
          {
            label: "Ventas",
            data: values,
            backgroundColor: "rgba(99,102,241,0.55)",
            hoverBackgroundColor: "rgba(99,102,241,0.8)",
            borderRadius: 6,
            maxBarThickness: 48,
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
            beginAtZero: true,
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
      <h2>Ventas últimos 7 días</h2>

      <div className="chart-container">
        <canvas ref={canvasRef} />
      </div>
    </section>
  );
}
