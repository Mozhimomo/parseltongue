import { Suspense } from "react";
import { LoginScreen } from "../components/login-screen";
export default function LoginPage() { return <Suspense fallback={<p role="status">正在准备登录…</p>}><LoginScreen/></Suspense>; }
