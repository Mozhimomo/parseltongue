"use client";
import { createContext, useContext, useEffect, useState, useCallback } from "react";
import { useAuth } from "./auth-provider";
import { sessionClient, errorMessage } from "../lib/api-client";
import { agentName, type Trial, type Replay } from "../lib/game";
export type TrialRecord = Trial & { agents: string[]; agentNames?: string[]; seed: number; maxTicks: number };
type GameContextValue = {
  records: TrialRecord[]; trial: Trial | null; replay: Replay | null; error: string; submitting: boolean; loading: boolean;
  selected: string[]; seed: string; maxTicks: string; setSelected: (v: string[])=>void; setSeed: (v:string)=>void; setMaxTicks: (v:string)=>void;
  start: (names?:Record<string,string>)=>Promise<void>; openTrial: (trial: TrialRecord)=>void; retry: ()=>void; clearRecords: ()=>void;
};
const Context = createContext<GameContextValue | null>(null);
export function GameProvider({children}:{children:React.ReactNode}) {
  const {user} = useAuth(); const owner = user?.id;
  const [records,setRecords] = useState<TrialRecord[]>([]), [trial,setTrial] = useState<Trial|null>(null), [replay,setReplay] = useState<Replay|null>(null);
  const [selected,setSelected] = useState(["cautious","greedy","forager","straight"]), [seed,setSeed] = useState("42"), [maxTicks,setMaxTicks] = useState("500");
  const [error,setError] = useState(""), [submitting,setSubmitting] = useState(false), [loading,setLoading] = useState(false), [retryCount,setRetryCount] = useState(0);
  const [loadedOwner,setLoadedOwner] = useState<number|undefined>();
  useEffect(()=>{
    let active = true;
    void Promise.resolve().then(()=>{
      if (!active) return;
      setSelected(["cautious","greedy","forager","straight"]); setTrial(null); setReplay(null); setError(""); setLoading(false); setSubmitting(false);
      let saved: TrialRecord[] = [];
      try {
        const value: unknown = owner ? JSON.parse(sessionStorage.getItem(`parseltongue.trials.${owner}`) || "[]") : [];
        if (Array.isArray(value)) saved = value.filter(r=>r && typeof r.id === "string" && /^[a-zA-Z0-9-]+$/.test(r.id) && Array.isArray(r.agents) && r.agents.length===4 && typeof r.seed === "number" && typeof r.maxTicks === "number").slice(0,24);
      } catch { /* Storage is optional; the app remains usable in private mode. */ }
      setRecords(saved); setLoadedOwner(owner);
      const activeTrial = saved.find(r=>r.status === "QUEUED" || r.status === "RUNNING");
      if(activeTrial) setTrial(activeTrial);
    });
    return ()=>{active=false;};
  },[owner]);
  useEffect(()=>{ if(owner && loadedOwner===owner) { try{ sessionStorage.setItem(`parseltongue.trials.${owner}`,JSON.stringify(records)); }catch{/* Memory state still works. */} } },[records,owner,loadedOwner]);
  const trialId = trial?.id;
  useEffect(()=>{
    if(!owner || !trialId) return;
    let active=true; let timer:ReturnType<typeof setTimeout>; const abort=new AbortController();
    const poll=async()=>{
      try {
        const status=await sessionClient.authenticated<Trial>(`/api/game/trials/${encodeURIComponent(trialId)}`,{signal:abort.signal});
        if(!active) return;
        setTrial(status); setRecords(current=>current.map(r=>r.id===trialId?{...r,...status}:r));
        if(status.status==="SUCCEEDED"){
          const recording=await sessionClient.authenticated<Replay>(`/api/game/trials/${encodeURIComponent(trialId)}/replay`,{signal:abort.signal});
          if(active){setReplay(recording);setLoading(false);setError("");}
        }else if(status.status==="FAILED") {setError("这场对局未能完成，请稍后重新开局。");setLoading(false);}
        else timer=setTimeout(poll,800);
      }catch(failure){if(active){setError(errorMessage(failure));setLoading(false);}}
    };
    void poll();return()=>{active=false;abort.abort();clearTimeout(timer);};
  },[owner,trialId,retryCount]);
  const start=async(names?:Record<string,string>)=>{
    if(!owner || submitting) return;
    if(!/^\d+$/.test(seed)||Number(seed)>2147483647||!/^\d+$/.test(maxTicks)||Number(maxTicks)<1||Number(maxTicks)>2000){setError("地图种子须为 0–2147483647 的整数，回合上限为 1–2000。");return;}
    setSubmitting(true);setError("");
    try{
      const settings={agents:selected,seed:Number(seed),maxTicks:Number(maxTicks)};
      const created=await sessionClient.authenticated<Trial>("/api/game/trials",{method:"POST",body:JSON.stringify(settings)});
      if(sessionClient.getSnapshot().user?.id!==owner)return;
      setTrial(created);setReplay(null);setLoading(true);setRecords(current=>[{...created,...settings,agentNames:selected.map(id=>names?.[id]??agentName(id))},...current].slice(0,24));
    }catch(failure){if(sessionClient.getSnapshot().user?.id===owner)setError(errorMessage(failure));}
    finally{setSubmitting(false);}
  };
  const openTrial=useCallback((record:TrialRecord)=>{setTrial(record);setReplay(null);setError("");setLoading(true);setSelected(record.agents);setSeed(String(record.seed));setMaxTicks(String(record.maxTicks));setRetryCount(n=>n+1);},[]);
  return <Context.Provider value={{records:loadedOwner===owner?records:[],trial:loadedOwner===owner?trial:null,replay:loadedOwner===owner?replay:null,error,submitting,loading,selected,seed,maxTicks,setSelected,setSeed,setMaxTicks,start,openTrial,retry:()=>{setError("");if(trial){setLoading(true);setRetryCount(n=>n+1);}},clearRecords:()=>setRecords([])}}>{children}</Context.Provider>;
}
export function useGame(){const value=useContext(Context);if(!value)throw new Error("GameProvider missing");return value;}
