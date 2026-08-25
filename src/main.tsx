import React from "react";
import ReactDOM from "react-dom/client";
import { getCurrentWindow } from "@tauri-apps/api/window";
import App from "./App";
import FloatList from "./FloatList";
import "./App.css";

// 一个入口按窗口 label 分流：main=设置页，float=浮动额度列表
function Root() {
  switch (getCurrentWindow().label) {
    case "float":
      return <FloatList />;
    default:
      return <App />;
  }
}

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <Root />
  </React.StrictMode>,
);
