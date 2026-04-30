  The plan is at /home/dcar/.claude/plans/i-notice-the-nws-idempotent-shannon.md. In short: add a third pass on the /gridpoints/{office}/{x,y}    
  JSON the app already fetches twice, parsing the maxTemperature and minTemperature arrays into a date-keyed map, then merging into the existing  
  NwsDayAccumulator.temperatureMap without overwriting any value /forecast already supplied. The merger runs between applyForecastPeriods and     
  removePhantomFutureDays so a previously-phantom terminal-low-only day (May 7 with low=51 from "Wednesday Night") gets upgraded to a full day and
   survives the filter naturally. UI is untouched — missing condition strings already render as "Unknown".                                        

  ★ Insight ─────────────────────────────────────
  - The phantom-day filter is load-bearing: I almost positioned the merger after removePhantomFutureDays, but that would have been wrong — the
  filter runs on a stale temperatureMap and removes the very day the merger is meant to upgrade. Ordering became a design constraint rather than a
   free choice. Worth noting: removePhantomFutureDays is one of those quietly important pure functions that protect the UI from incomplete-day    
  artifacts; understanding its precondition (high=null, low=present) is what made the integration point obvious.                                  
  - NWS's two endpoints are different products with the same data underneath: /forecast is the forecaster's product — curated text, half-day
  periods, ~14 entries. /gridpoints/{office}/{x,y} is the raw model output — typed arrays, ~8 days. Plenty of weather apps choose one or the
  other; few read both. Reading both gives you the curated text where it exists and the longer horizon where it doesn't, which is exactly the     
  trade you want.                                                                                                                            
  - The HTTP refactor I deliberately deferred: there are now three separate httpClient.get() calls to the identical gridpoints URL (getSkyCover,  
  getQuantitativePrecipitation, the new method). The right architecture is one fetch + three parse passes via a shared getGridpointsBundle(). I 
  deferred it because (a) the existing async block already overlaps the redundant requests in parallel, so wall-clock cost is roughly one server  
  cache hit, and (b) consolidating now expands blast radius to two already-tested call sites. It's a clean follow-up ticket.                    
  ─────────────────────────────────────────────────                                                                                               
                                                        
  The main risk to call out: minTemperature validTime semantics — those overnight intervals cross a date boundary, and the proposed               
  end.minusMinutes(1) attribution is an educated guess that mirrors what applyForecastPeriods already does for /forecast "Night" periods. The plan
   includes a one-time diagnostic log so you can verify the first real merge against the corresponding /forecast "X Night" period before declaring
   it done.     
 
