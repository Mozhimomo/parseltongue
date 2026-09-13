import { Suspense } from "react";
import { LoginScreen } from "../components/login-screen";
export default function RegisterPage() { return <Suspense fallback={<p role="status">正在准备注册…</p>}><LoginScreen register/></Suspense>; }
