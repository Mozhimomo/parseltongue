import test from "node:test";
import assert from "node:assert/strict";
import { createSessionClient, ApiError } from "../app/lib/api-client.ts";
const data=(token="first",id=1)=>({accessToken:token,expiresInSeconds:1800,user:{id,username:`player_${id}`,role:"USER"}});
const ok=value=>new Response(JSON.stringify({code:0,message:"ok",data:value}),{status:200});
const fail=(status,message="failure")=>new Response(JSON.stringify({code:status*100,message,data:null}),{status});

test("session initialization shares one refresh and keeps credentials out of public state",async()=>{
  let calls=0;const client=createSessionClient(async()=>{calls++;return ok(data("private-token"));});
  await Promise.all([client.initialize(),client.initialize(),client.initialize()]);
  assert.equal(calls,1);assert.equal(client.getSnapshot().status,"authenticated");
  assert.equal(JSON.stringify(client.getSnapshot()).includes("private-token"),false);
});
test("concurrent expired requests refresh once and retry with the new token",async()=>{
  let refreshes=0;const observed=[];
  const client=createSessionClient(async(path,init)=>{
    if(path==="/api/auth/refresh")return ok(data(++refreshes===1?"expired":"renewed"));
    const bearer=init.headers.get("Authorization");observed.push(bearer);
    return bearer==="Bearer expired"?fail(401):ok({id:1});
  });
  await client.initialize();await Promise.all([client.authenticated("/a"),client.authenticated("/b")]);
  assert.equal(refreshes,2);assert.equal(observed.filter(v=>v==="Bearer renewed").length,2);
});
test("revoked refresh clears login and never loops",async()=>{
  let refreshes=0;const client=createSessionClient(async(path)=>path==="/api/auth/refresh"?(++refreshes===1?ok(data()):fail(401)):fail(401));
  await client.initialize();await assert.rejects(client.authenticated("/api/game/agents"),ApiError);
  assert.equal(refreshes,2);assert.equal(client.getSnapshot().status,"guest");
});
test("late restoration cannot overwrite a newly logged in account",async()=>{
  let resolve;const old=new Promise(r=>{resolve=r;});
  const client=createSessionClient(async path=>path==="/api/auth/refresh"?old:ok(data("new-account",2)));
  const init=client.initialize();await client.login("player_2","password123");resolve(ok(data("old-account",1)));await init;
  assert.equal(client.getSnapshot().user.id,2);
});
test("failed logout does not report that the server session was revoked",async()=>{
  const client=createSessionClient(async path=>path==="/api/auth/refresh"?ok(data()):fail(503,"internal details"));
  await client.initialize();await assert.rejects(client.logout(),e=>e instanceof ApiError&&e.status===503&&!e.message.includes("internal details"));
  assert.equal(client.getSnapshot().status,"authenticated");
});
test("logout all uses protected endpoint and clears in-memory identity",async()=>{
  let logoutRequest;const client=createSessionClient(async(path,init)=>{if(path==="/api/auth/refresh")return ok(data());logoutRequest={path,init};return ok(null);});
  await client.initialize();await client.logout(true);
  assert.equal(logoutRequest.path,"/api/auth/logout-all");assert.equal(logoutRequest.init.method,"POST");assert.equal(logoutRequest.init.credentials,"include");
  assert.equal(logoutRequest.init.headers.get("Authorization"),"Bearer first");assert.equal(client.getSnapshot().user,null);
});
test("backend envelope errors and malformed success responses are not accepted",async()=>{
  const client=createSessionClient(async()=>new Response('{"code":40000,"message":"invalid"}',{status:200}));
  await assert.rejects(client.raw("/x"),ApiError);
  const malformed=createSessionClient(async()=>new Response("not json",{status:200}));
  await assert.rejects(malformed.raw("/x"),ApiError);
});
test("registration sends only supplied credentials and reports duplicate username",async()=>{
  let sent;const client=createSessionClient(async(path,init)=>{sent={path,init};return fail(409,"用户名已被使用");});
  await assert.rejects(client.register("chosen_name","password123"),e=>e.status===409);
  assert.equal(sent.path,"/api/auth/register");assert.equal(sent.init.headers.get("Authorization"),null);
  assert.deepEqual(JSON.parse(sent.init.body),{username:"chosen_name",password:"password123"});
});
