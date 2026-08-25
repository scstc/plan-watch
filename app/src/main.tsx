import React from "react";
import ReactDOM from "react-dom/client";
import { getCurrentWindow } from "@tauri-apps/api/window";
import SettingsApp from "./settings/SettingsApp";
import FloatList from "./float/FloatList";
import "./styles/base.css";
import "./styles/settings.css";
import "./styles/float.css";

// 一个入口按窗口 label 分流：main=设置页，float=浮动额度列表
function Root() {
  switch (getCurrentWindow().label) {
    case "float":
      return <FloatList />;
    default:
      return <SettingsApp />;
  }
}

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <Root />
  </React.StrictMode>,
);
