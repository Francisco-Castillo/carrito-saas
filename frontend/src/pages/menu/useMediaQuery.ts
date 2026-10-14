import { useEffect, useState } from "react";

/**
 * Media-query hook for the cart presentation (bar + sheet on phones, sticky
 * sidebar from 1024px up). jsdom (and old browsers) have no matchMedia: it
 * degrades to `false`, i.e. the mobile presentation, which is the default the
 * tests exercise.
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() =>
    typeof window.matchMedia === "function" ? window.matchMedia(query).matches : false,
  );

  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;
    const mql = window.matchMedia(query);
    const onChange = (event: MediaQueryListEvent) => setMatches(event.matches);
    setMatches(mql.matches);
    mql.addEventListener("change", onChange);
    return () => mql.removeEventListener("change", onChange);
  }, [query]);

  return matches;
}
