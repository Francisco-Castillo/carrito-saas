import { useEffect, useRef } from "react";
import Chart from "chart.js/auto";
import type { SalesByDay } from "./api";
import { formatDayLabel } from "./format";

interface SalesByDayChartProps {
  /** null = sin datos aún: el canvas queda vacío hasta la primera respuesta. */
  data: SalesByDay[] | null;
  /** Semana anterior (misma longitud/orden): línea comparativa + % variación. */
  prevData?: SalesByDay[] | null;
  /** Primera carga en curso: esqueleto del panel en vez del panel vacío. */
  loading?: boolean;
  /** La API falló y no hay datos: mensaje visible en vez del gráfico vacío. */
  error?: boolean;
}

/** Variación % del total de la semana actual contra la anterior. */
function weekVariation(data: SalesByDay[], prev: SalesByDay[]): number | null {
  const total = data.reduce((sum, d) => sum + d.revenue, 0);
  const prevTotal = prev.reduce((sum, d) => sum + d.revenue, 0);

  // Sin base de comparación no hay % honesto que mostrar.
  if (prevTotal === 0) {
    return null;
  }

  return ((total - prevTotal) / prevTotal) * 100;
}

/**
 * Gráfico de barras de las ventas de los últimos 7 días (hoy incluido).
 * El backend siempre devuelve la serie completa de 7 días, con 0 en los días
 * sin ventas, así que el eje X es estable. Mismo estilo Chart.js que
 * SalesChart (línea → barras para distinguir el corte temporal).
 */
export default function SalesByDayChart({ data, prevData, loading = false, error = false }: SalesByDayChartProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const chartRef = useRef<Chart | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !data) return;

    const labels = data.map((d) => formatDayLabel(d.day));
    const values = data.map((d) => d.revenue);
    // La semana anterior alinea por índice: el backend devuelve ambas series
    // día-más-viejo-primero, misma longitud.
    const prevValues = prevData && prevData.length === data.length
      ? prevData.map((d) => d.revenue)
      : null;

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
            order: 2,
          },
          ...(prevValues
            ? [
                {
                  label: "Semana anterior",
                  data: prevValues,
                  type: "line" as const,
                  borderColor: "rgba(232,121,249,0.8)",
                  backgroundColor: "rgba(232,121,249,0.15)",
                  borderDash: [6, 4],
                  pointRadius: 2,
                  tension: 0.3,
                  order: 1,
                },
              ]
            : []),
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

  const variation = data && prevData ? weekVariation(data, prevData) : null;

  return (
    <section className="chart-section panel glass-panel fade-up">
      <div className="panel-header">
        <h2>Ventas últimos 7 días</h2>
        {variation !== null && (
          <span
            className={`variation-chip ${variation >= 0 ? "variation-chip--up" : "variation-chip--down"}`}
          >
            {variation >= 0 ? "▲" : "▼"} {Math.abs(variation).toFixed(1)}% vs. semana anterior
          </span>
        )}
      </div>

      {loading && !data ? (
        <div className="chart-container skeleton" aria-busy="true" aria-label="Cargando ventas semanales" />
      ) : error && !data ? (
        <div className="section-error" aria-live="polite">
          <span>No se pudieron cargar las ventas semanales.</span>
          <p>Reintentamos automáticamente cada minuto.</p>
        </div>
      ) : (
        <div className="chart-container">
          <canvas ref={canvasRef} />
        </div>
      )}
    </section>
  );
}
