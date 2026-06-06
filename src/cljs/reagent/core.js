import * as squint_core from 'squint-cljs/core.js';
import { updateListeners } from './dom.js';

export function atom(init) {
  const a = squint_core.atom(init);
  if (a && typeof a._add_watch === 'function') {
    a._add_watch('reagent-update-watcher', () => {
      updateListeners.forEach(l => l());
    });
  }
  return a;
}
