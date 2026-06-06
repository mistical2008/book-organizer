import * as squint_core from 'squint-cljs/core.js';
import * as r from 'reagent.core';
import * as reagent_DOT_core from 'reagent.core';
import * as rdom from 'reagent.dom';
import * as reagent_DOT_dom from 'reagent.dom';
import * as str from 'squint-cljs/src/squint/string.js';
import * as clojure_DOT_string from 'squint-cljs/src/squint/string.js';
var app_state = r.atom(({"active-tab": "sandbox", "config": ({}), "edit-config": ({}), "scanned-books": ({}), "logs": [], "isbn-input": "", "is-scanning": false, "save-success": false}));
var fetch_config_BANG_ = function () {
return fetch("/api/config").then((function (resp) {
return resp.json();

})).then((function (data) {
squint_core.swap_BANG_(app_state, squint_core.assoc, "config", data);
return squint_core.swap_BANG_(app_state, squint_core.assoc, "edit-config", data);

}));

};
var fetch_state_BANG_ = function () {
return fetch("/api/state").then((function (resp) {
return resp.json();

})).then((function (data) {
const scanned1 = (() => {
const or__23450__auto__2 = squint_core.get(data, "scanned_books");
if (squint_core.truth_(or__23450__auto__2)) {
return or__23450__auto__2} else {
const or__23450__auto__3 = squint_core.get(data, "scanned_books");
if (squint_core.truth_(or__23450__auto__3)) {
return or__23450__auto__3} else {
return []};
};

})();
const orgs4 = (() => {
const or__23450__auto__5 = squint_core.get(data, "file_organization");
if (squint_core.truth_(or__23450__auto__5)) {
return or__23450__auto__5} else {
const or__23450__auto__6 = squint_core.get(data, "file_organization");
if (squint_core.truth_(or__23450__auto__6)) {
return or__23450__auto__6} else {
return []};
};

})();
const orgs_map7 = squint_core.into(({}), squint_core.map((function (o) {
return [(() => {
const or__23450__auto__8 = squint_core.get(o, "filepath");
if (squint_core.truth_(or__23450__auto__8)) {
return or__23450__auto__8} else {
return squint_core.get(o, "filepath")};

})(), o];

}), orgs4));
const books_map9 = squint_core.into(({}), squint_core.map((function (sb) {
const path10 = (() => {
const or__23450__auto__11 = squint_core.get(sb, "filepath");
if (squint_core.truth_(or__23450__auto__11)) {
return or__23450__auto__11} else {
return squint_core.get(sb, "filepath")};

})();
const org12 = squint_core.get(orgs_map7, path10);
const entry13 = ({"status": (() => {
const or__23450__auto__14 = squint_core.get(sb, "status");
if (squint_core.truth_(or__23450__auto__14)) {
return or__23450__auto__14} else {
return squint_core.get(sb, "status")};

})()});
return [path10, ((squint_core.truth_(org12)) ? (({...entry13,"destination":(() => {
const or__23450__auto__15 = squint_core.get(org12, "dest_path");
if (squint_core.truth_(or__23450__auto__15)) {
return or__23450__auto__15} else {
return squint_core.get(org12, "dest_path")};

})(),"meta":({"title": (() => {
const or__23450__auto__16 = squint_core.get(org12, "title");
if (squint_core.truth_(or__23450__auto__16)) {
return or__23450__auto__16} else {
return squint_core.get(org12, "title")};

})(), "author": (() => {
const or__23450__auto__17 = squint_core.get(org12, "author");
if (squint_core.truth_(or__23450__auto__17)) {
return or__23450__auto__17} else {
return squint_core.get(org12, "author")};

})(), "year": (() => {
const or__23450__auto__18 = squint_core.get(org12, "year");
if (squint_core.truth_(or__23450__auto__18)) {
return or__23450__auto__18} else {
return squint_core.get(org12, "year")};

})(), "isbn": (() => {
const or__23450__auto__19 = squint_core.get(org12, "isbn");
if (squint_core.truth_(or__23450__auto__19)) {
return or__23450__auto__19} else {
return squint_core.get(org12, "isbn")};

})(), "genre": (() => {
const or__23450__auto__20 = squint_core.get(org12, "genre");
if (squint_core.truth_(or__23450__auto__20)) {
return or__23450__auto__20} else {
return squint_core.get(org12, "genre")};

})()})})) : (entry13))];

}), scanned1));
return squint_core.swap_BANG_(app_state, squint_core.assoc, "scanned-books", books_map9);

}));

};
var fetch_logs_BANG_ = function () {
return fetch("/api/logs").then((function (resp) {
return resp.json();

})).then((function (data) {
return squint_core.swap_BANG_(app_state, squint_core.assoc, "logs", data);

}));

};
var header_component = function () {
return ["nav.flex.items-center.justify-between.px-8.py-4.border-b.border-white-5.bg-dark-0D", ["div.flex.items-center.space-x-4", ["div.w-10.h-10.bg-brand.rounded.flex.items-center.justify-center", ["span.text-black.font-mono.font-bold.text-xl", "L"]], ["div", ["h1.text-xl.font-serif.text-white", "Librarian Book Organiser"], ["p.text-xs.font-mono.text-brand", "STATEFUL CLASSIFICATION DAEMON"]]], ["div.flex.space-x-2", squint_core.lazy((function* () {
for (let G__1 of squint_core.iterable([["sandbox", "Sandbox Hub"], ["generator", "Daemon Configuration"]])) {
const vec__25 = G__1;
const tab6 = squint_core.nth(vec__25, 0, null);
const label7 = squint_core.nth(vec__25, 1, null);
yield ["button.px-4.py-2.rounded.text-xs.font-medium", ({"key": tab6, "class": ((squint_core._EQ_(squint_core.get(squint_core.deref(app_state), "active-tab"), tab6)) ? ("bg-brand text-black font-semibold") : ("text-gray-400 hover:text-white transition-colors")), "on-click": (function () {
return squint_core.swap_BANG_(app_state, squint_core.assoc, "active-tab", tab6);

})}), label7];
}
return null;

}))]];

};
var content_sandbox = function () {
const books1 = (() => {
const b2 = squint_core.get(squint_core.deref(app_state), "scanned-books");
if (squint_core.truth_(squint_core.map_QMARK_(b2))) {
return b2} else {
return squint_core.into(({}), b2)};

})();
const logs3 = squint_core.get(squint_core.deref(app_state), "logs");
const is_scanning4 = squint_core.get(squint_core.deref(app_state), "is-scanning");
return ["div.space-y-6", ["div.bg-dark-12.p-6.rounded-xl.border.border-white-5.space-y-4", ["div.flex.flex-col.md:flex-row.md:items-center.justify-between.gap-4", ["div", ["h3.text-lg.font-serif.text-brand", "⚡ Interactive Classification Sandbox"], ["p.text-xs.text-gray-400", "Queue file paths manually to simulate cataloguing runs, or run active polling daemon checks."]], ["div.flex.space-x-3", ["button.px-4.py-2.rounded.text-xs.font-bold.transition-colors", ({"class": ((squint_core.truth_(is_scanning4)) ? ("bg-gray-700 text-gray-400 cursor-not-allowed") : ("bg-brand text-black hover:bg-brand-hover")), "disabled": is_scanning4, "on-click": (function () {
squint_core.swap_BANG_(app_state, squint_core.assoc, "is-scanning", true);
return fetch("/api/scan", ({"method": "POST"})).then((function (resp) {
return resp.json();

})).then((function (res) {
squint_core.swap_BANG_(app_state, squint_core.assoc, "is-scanning", false);
fetch_state_BANG_();
return fetch_logs_BANG_();

})).catch((function (err) {
squint_core.swap_BANG_(app_state, squint_core.assoc, "is-scanning", false);
return console.error(err);

}));

})}), ((squint_core.truth_(is_scanning4)) ? ("Running Classification Engine...") : ("Run Polling Check"))], ["button.border.border-white-10.text-gray-300.px-4.py-2.rounded.text-xs.font-medium.hover:bg-brand-soft.transition-colors", ({"on-click": (function () {
fetch_state_BANG_();
fetch_logs_BANG_();
return fetch_config_BANG_();

})}), "Sync UI State"]]], ["div.flex.gap-2", ["input.flex-1.bg-black.border.border-white-10.p-2.rounded.text-xs.text-white.focus:border-brand-muted.focus:outline-none.font-mono", ({"type": "text", "placeholder": "File Path inside intake folder (e.g. /data/books_to_sort/book_ocr_sample.pdf)", "value": squint_core.get(squint_core.deref(app_state), "isbn-input"), "on-change": (function (_PERCENT_1) {
return squint_core.swap_BANG_(app_state, squint_core.assoc, "isbn-input", _PERCENT_1.target.value);

})})], ["button.bg-brand.text-black.px-4.py-2.rounded.text-xs.font-bold.hover:bg-brand-hover.transition-colors", ({"on-click": (function () {
const inp5 = str.trim(squint_core.get(squint_core.deref(app_state), "isbn-input"));
if (squint_core.truth_(str.blank_QMARK_(inp5))) {
return null} else {
squint_core.swap_BANG_(app_state, squint_core.assoc_in, ["scanned-books", inp5], ({"status": "queued", "meta": ({"title": "Simulated File Entry", "author": "Click 'Run Polling Check' to resolve"})}));
return squint_core.swap_BANG_(app_state, squint_core.assoc, "isbn-input", "");
};

})}), "Queue Book File"]]], ["div.grid.grid-cols-1.lg:grid-cols-2.gap-6", ["div.bg-dark-12.p-6.rounded-xl.border.border-white-5.flex.flex-col", ["h3.text-lg.font-serif.text-white.mb-4", "🗃️ Monitored Folder Index Stream"], ((squint_core.truth_(squint_core.empty_QMARK_(books1))) ? (["p.text-xs.text-gray-500", "Intake stream queue is empty. Ready for scanning or simulator items."]) : (["div.space-y-3.max-h-96.overflow-y-auto.pr-1", squint_core.lazy((function* () {
for (let G__6 of squint_core.iterable(books1)) {
const vec__710 = G__6;
const path11 = squint_core.nth(vec__710, 0, null);
const entry12 = squint_core.nth(vec__710, 1, null);
yield ["div.p-3.rounded.border.border-white-5.bg-black-30.space-y-2", ["div.flex.items-start.justify-between.gap-4", ["div.flex-1.min-w-0", ["p.text-xs.font-mono.text-gray-400.truncate", path11], (yield* (function* () {
const meta13 = squint_core.get(entry12, "meta");
if (squint_core.truth_(meta13)) {
return ["div.mt-1", ["p.text-sm.font-serif.text-brand.font-medium", squint_core.get(meta13, "title")], ["p.text-xs.text-gray-300", `${"by "}${squint_core.get(meta13, "author")??''}${" ("}${(yield* (function* () {
const or__23450__auto__14 = squint_core.get(meta13, "year");
if (squint_core.truth_(or__23450__auto__14)) {
return or__23450__auto__14} else {
return "N/A"};

})())??''}${")"}`], ["p.text-xs.text-gray-400.font-mono", `${"ISBN: "}${(yield* (function* () {
const or__23450__auto__15 = squint_core.get(meta13, "isbn");
if (squint_core.truth_(or__23450__auto__15)) {
return or__23450__auto__15} else {
return "None"};

})())??''}${" | Genre: "}${(yield* (function* () {
const or__23450__auto__16 = squint_core.get(meta13, "genre");
if (squint_core.truth_(or__23450__auto__16)) {
return or__23450__auto__16} else {
return "N/A"};

})())??''}`]]} else {
return ["p.text-xs.text-gray-500", "Pending classification scan..."]};

})())], ["span.text-xs.uppercase.px-2.py-1.rounded.font-mono.font-semibold.h-fit", ({"class": (yield* (function* () {
const G__117 = squint_core.get(entry12, "status");
switch (G__117) {case "completed":
return "bg-emerald-950/80 text-emerald-400 border border-emerald-900";

break;
case "pending":
return "bg-amber-950/80 text-amber-400 border border-amber-900";

break;
case "queued":
return "bg-blue-950/80 text-blue-400 border border-blue-900";

break;
case "low_confidence":
return "bg-rose-950/80 text-rose-400 border border-rose-900";

break;
default:
return "bg-white-5 text-brand border border-white-10"};

})())}), (yield* (function* () {
const or__23450__auto__19 = squint_core.get(entry12, "status");
if (squint_core.truth_(or__23450__auto__19)) {
return or__23450__auto__19} else {
return "unknown"};

})())]], ((squint_core.truth_(squint_core.get(entry12, "destination"))) ? (["p.text-xs.font-mono.text-emerald-400.p-2.rounded", ({"class": "bg-emerald-950/30 border border-emerald-900/40"}), "🚚 Destination: ", squint_core.get(entry12, "destination")]) : (null))];
}
return null;

}))]))], ["div.bg-dark-12.p-6.rounded-xl.border.border-white-5.flex.flex-col", ["h3.text-lg.font-serif.text-white.mb-4", "📋 Classification Logs Stream"], ((squint_core.truth_(squint_core.empty_QMARK_(logs3))) ? (["p.text-xs.text-gray-500", "No classification events recorded yet. Run a check cycle to begin."]) : (["div.bg-black.p-3.rounded.border.border-white-10.font-mono.text-xs.text-gray-300.flex-1.max-h-96.overflow-y-auto.space-y-1", squint_core.lazy((function* () {
for (let G__20 of squint_core.iterable(squint_core.map_indexed(squint_core.vector, logs3))) {
const vec__2124 = G__20;
const idx25 = squint_core.nth(vec__2124, 0, null);
const log_line26 = squint_core.nth(vec__2124, 1, null);
yield ["p.text-xs.leading-relaxed.whitespace-pre-wrap", ({"key": idx25, "class": ((squint_core.truth_((yield* (function* () {
const or__23450__auto__27 = str.includes_QMARK_(log_line26, "failure");
if (squint_core.truth_(or__23450__auto__27)) {
return or__23450__auto__27} else {
const or__23450__auto__28 = str.includes_QMARK_(log_line26, "failed");
if (squint_core.truth_(or__23450__auto__28)) {
return or__23450__auto__28} else {
return str.includes_QMARK_(log_line26, "⚠️")};
};

})()))) ? ("text-rose-400") : (((squint_core.truth_((yield* (function* () {
const or__23450__auto__29 = str.includes_QMARK_(log_line26, "Success");
if (squint_core.truth_(or__23450__auto__29)) {
return or__23450__auto__29} else {
const or__23450__auto__30 = str.includes_QMARK_(log_line26, "successfully");
if (squint_core.truth_(or__23450__auto__30)) {
return or__23450__auto__30} else {
return str.includes_QMARK_(log_line26, "✅")};
};

})()))) ? ("text-emerald-400") : (((squint_core.truth_((yield* (function* () {
const or__23450__auto__31 = str.includes_QMARK_(log_line26, "Resolved");
if (squint_core.truth_(or__23450__auto__31)) {
return or__23450__auto__31} else {
const or__23450__auto__32 = str.includes_QMARK_(log_line26, "🔍");
if (squint_core.truth_(or__23450__auto__32)) {
return or__23450__auto__32} else {
return str.includes_QMARK_(log_line26, "✨")};
};

})()))) ? ("text-brand") : ((("else") ? ("text-gray-400") : (null))))))))}), log_line26];
}
return null;

}))]))]]];

};
var content_generator = function () {
const edit_config1 = squint_core.get(squint_core.deref(app_state), "edit-config");
const save_status2 = squint_core.get(squint_core.deref(app_state), "save-success");
return ["div.bg-dark-12.p-6.rounded-xl.border.border-white-5.max-w-3xl.mx-auto.space-y-6", ["div.border-b.border-white-5.pb-4", ["h3.text-xl.font-serif.text-brand", "⚙️ Daemon Core Settings Configuration"], ["p.text-xs.text-gray-400", "Configure internal directory listeners, metadata confidence thresholds, caching, and ISBN retrieval rules."]], ((squint_core.truth_(save_status2)) ? (["div.p-3.rounded.bg-emerald-950.border.border-emerald-800.text-emerald-400.text-xs.flex.items-center.justify-between", ["span", "✓ Configuration parameters written successfully to data/config.json!"], ["button.text-emerald-300.font-bold", ({"on-click": (function () {
return squint_core.swap_BANG_(app_state, squint_core.assoc, "save-success", false);

})}), "Dismiss"]]) : (null)), ["div.grid.grid-cols-1.md:grid-cols-2.gap-6", ["div.space-y-4", ["div.space-y-1.5", ["label.block.text-xs.font-serif.text-gray-300", "Monitored Folders (Comma separated)"], ["input.w-full.bg-black.border.border-white-10.p-2.rounded.text-xs.text-white.focus:border-brand-muted.focus:outline-none", ({"type": "text", "value": (() => {
const dirs3 = squint_core.get(edit_config1, "inputDirs");
if (squint_core.truth_(squint_core.vector_QMARK_(dirs3))) {
return str.join(", ", dirs3)} else {
return dirs3};

})(), "on-change": (function (_PERCENT_1) {
return squint_core.swap_BANG_(app_state, squint_core.assoc_in, ["edit-config", "inputDirs"], _PERCENT_1.target.value);

})})]], ["div.space-y-1.5", ["label.block.text-xs.font-serif.text-gray-300", "Output Category Destination Folder"], ["input.w-full.bg-black.border.border-white-10.p-2.rounded.text-xs.text-white.focus:border-brand-muted.focus:outline-none", ({"type": "text", "value": squint_core.get(edit_config1, "outputDir"), "on-change": (function (_PERCENT_1) {
return squint_core.swap_BANG_(app_state, squint_core.assoc_in, ["edit-config", "outputDir"], _PERCENT_1.target.value);

})})]], ["div.space-y-1.5", ["label.block.text-xs.font-serif.text-gray-300", "Dynamic Directory File Template"], ["input.w-full.bg-black.border.border-white-10.p-2.rounded.text-xs.text-white.focus:border-brand-muted.focus:outline-none", ({"type": "text", "value": squint_core.get(edit_config1, "destinationTemplate"), "on-change": (function (_PERCENT_1) {
return squint_core.swap_BANG_(app_state, squint_core.assoc_in, ["edit-config", "destinationTemplate"], _PERCENT_1.target.value);

})})]], ["div.space-y-1.5", ["label.block.text-xs.font-serif.text-gray-300", "Minimum Confidence Threshold (%)"], ["input.w-full.bg-black.border.border-white-10.p-2.rounded.text-xs.text-white.focus:border-brand-muted.focus:outline-none", ({"type": "number", "min": "0", "max": "100", "value": `${squint_core.get(edit_config1, "confidenceThreshold")??''}`, "on-change": (function (_PERCENT_1) {
return squint_core.swap_BANG_(app_state, squint_core.assoc_in, ["edit-config", "confidenceThreshold"], parseInt(_PERCENT_1.target.value, 10));

})})]]], ["div.space-y-4", ["div.space-y-1.5", ["label.block.text-xs.font-serif.text-gray-300", "Fallback Gemini Model Selector"], ["select.w-full.bg-black.border.border-white-10.p-2.rounded.text-xs.text-white.focus:border-brand-muted.focus:outline-none", ({"value": squint_core.get(edit_config1, "geminiModel", "gemini-3.5-flash"), "on-change": (function (_PERCENT_1) {
return squint_core.swap_BANG_(app_state, squint_core.assoc_in, ["edit-config", "geminiModel"], _PERCENT_1.target.value);

})}), ["option", ({"value": "gemini-3.5-flash"}), "gemini-3.5-flash (Fast & Accurate)"], ["option", ({"value": "gemini-1.5-pro"}), "gemini-1.5-pro (Aesthetic deep parsing)"]]], ["div.space-y-1.5", ["label.block.text-xs.font-serif.text-gray-300", "Google Books API Key"], ["input.w-full.bg-black.border.border-white-10.p-2.rounded.text-xs.text-white.focus:border-brand-muted.focus:outline-none.font-mono", ({"type": "text", "placeholder": "Enter Google Books API Key", "value": squint_core.get(edit_config1, "googleBooksApiKey", ""), "on-change": (function (_PERCENT_1) {
return squint_core.swap_BANG_(app_state, squint_core.assoc_in, ["edit-config", "googleBooksApiKey"], _PERCENT_1.target.value);

})})]], ["div.space-y-3.pt-3", ["label.flex.items-start.space-x-3.cursor-pointer.p-3.rounded.bg-black-30.border.border-white-5.hover:border-brand-muted.transition-colors", ["input.mt-1", ({"type": "checkbox", "checked": squint_core.get(edit_config1, "isbnOnlyRequests", false), "on-change": (function (_PERCENT_1) {
return squint_core.swap_BANG_(app_state, squint_core.assoc_in, ["edit-config", "isbnOnlyRequests"], _PERCENT_1.target.checked);

})})], ["div", ["span.text-xs.font-serif.text-brand.font-semibold", "ISBN Only Requests"], ["p.text-xs.text-gray-400", "Strictly query open books APIs for metadata. Disables fallback cataloguing via Gemini AI models."]]], ["label.flex.items-start.space-x-3.cursor-pointer.p-3.rounded.bg-black-30.border.border-white-5.hover:border-brand-muted.transition-colors", ["input.mt-1", ({"type": "checkbox", "checked": squint_core.get(edit_config1, "enableCaching", false), "on-change": (function (_PERCENT_1) {
return squint_core.swap_BANG_(app_state, squint_core.assoc_in, ["edit-config", "enableCaching"], _PERCENT_1.target.checked);

})})], ["div", ["span.text-xs.font-serif.text-white.font-semibold", "Intake Registry File Caching"], ["p.text-xs.text-gray-400", "Avoid duplicates by keeping track of successfully completed files, saving bandwidth."]]], ["label.flex.items-start.space-x-3.cursor-pointer.p-3.rounded.bg-black-30.border.border-white-5.hover:border-brand-muted.transition-colors", ["input.mt-1", ({"type": "checkbox", "checked": squint_core.get(edit_config1, "daemonEnabled", false), "on-change": (function (_PERCENT_1) {
return squint_core.swap_BANG_(app_state, squint_core.assoc_in, ["edit-config", "daemonEnabled"], _PERCENT_1.target.checked);

})})], ["div", ["span.text-xs.font-serif.text-white.font-semibold", "Daemon Automated Scheduler"], ["p.text-xs.text-gray-400", "Permit background system loops to poll intake folders automatically."]]]]]], ["div.flex.justify-end.space-x-3.border-t.border-white-5.pt-4", ["button.bg-brand.text-black.px-6.py-2.rounded.text-xs.font-bold.hover:bg-brand-hover.transition-colors", ({"on-click": (function () {
const cur_config4 = squint_core.get(squint_core.deref(app_state), "edit-config");
const input_dirs5 = (() => {
const dirs6 = squint_core.get(cur_config4, "inputDirs");
if (squint_core.truth_(squint_core.string_QMARK_(dirs6))) {
return squint_core.vec(squint_core.map(str.trim, str.split(dirs6, /,/)))} else {
return squint_core.vec(dirs6)};

})();
const sanitized_config7 = squint_core.assoc(cur_config4, "inputDirs", input_dirs5);
return fetch("/api/config", ({"method": "POST", "headers": ({"Content-Type": "application/json"}), "body": JSON.stringify(squint_core.clj__GT_js(sanitized_config7))})).then((function (resp) {
return resp.json();

})).then((function (res) {
squint_core.swap_BANG_(app_state, squint_core.assoc, "save-success", true);
return fetch_config_BANG_();

})).catch((function (err) {
return console.error(err);

}));

})}), "Save Configuration"]]];

};
var main_layout = function () {
return ["div.min-h-screen.bg-black.text-gray-100.font-sans", [header_component], ["main.max-w-7xl.mx-auto.p-6", (() => {
const G__21 = squint_core.get(squint_core.deref(app_state), "active-tab");
switch (G__21) {case "sandbox":
return [content_sandbox];

break;
case "generator":
return [content_generator];

break;
default:
return [content_sandbox]};

})()]];

};
var init = function () {
fetch_config_BANG_();
fetch_state_BANG_();
fetch_logs_BANG_();
setInterval((function () {
fetch_state_BANG_();
return fetch_logs_BANG_();

}), 5000);
return rdom.render([main_layout], document.getElementById("root"));

};

export { content_generator, fetch_config_BANG_, fetch_state_BANG_, main_layout, content_sandbox, init, header_component, fetch_logs_BANG_, app_state }
