import React, { useState, useEffect } from 'react';
import ReactDOM from 'react-dom/client';
import * as squint_core from 'squint-cljs/core.js';

// Setup our global atom subscription hook
export const updateListeners = new Set();

// Recursive Hiccup to React elements transpiler
export function hiccupToReact(hiccup) {
  if (hiccup === null || hiccup === undefined) return null;

  if (hiccup && typeof hiccup !== 'string' && typeof hiccup[Symbol.iterator] === 'function' && !React.isValidElement(hiccup) && !Array.isArray(hiccup)) {
    const items = Array.from(hiccup);
    const resolvedItems = items.map((item, idx) => {
      const el = hiccupToReact(item);
      if (React.isValidElement(el) && !el.key) {
        return React.cloneElement(el, { key: idx });
      }
      return el;
    });
    return React.createElement(React.Fragment, null, ...resolvedItems);
  }

  if (!Array.isArray(hiccup)) {
    // If it's a React element or a string/number
    return hiccup;
  }

  const [first, ...rest] = hiccup;
  if (!first) return null;

  if (typeof first === 'function') {
    // Component functions
    const res = first(...rest);
    return hiccupToReact(res);
  }

  if (typeof first === 'string') {
    // Standard HTML Tag with optional classes separator
    const parts = first.split('.');
    const tag = parts[0] || 'div';
    const classes = parts.slice(1).join(' ');

    let props = {};
    let children = rest;

    // Check if second element is attrs map
    const second = rest[0];
    if (second && typeof second === 'object' && !Array.isArray(second) && !React.isValidElement(second) && typeof second[Symbol.iterator] !== 'function') {
      props = { ...second };
      children = rest.slice(1);
    }

    if (classes) {
      props.className = props.className ? `${classes} ${props.className}` : classes;
    }

    const reactProps = {};
    for (const [k, v] of Object.entries(props)) {
      let cleanK = k;
      if (k === 'class') cleanK = 'className';
      else if (k === 'on-click') cleanK = 'onClick';
      else if (k === 'on-change') cleanK = 'onChange';
      else if (k === 'for') cleanK = 'htmlFor';
      else if (k === 'colspan') cleanK = 'colSpan';
      reactProps[cleanK] = v;
    }

    const renderedChildren = children.map((c, i) => {
      const el = hiccupToReact(c);
      if (React.isValidElement(el) && !el.key) {
        return React.cloneElement(el, { key: i });
      }
      return el;
    });

    if (tag === '<>' || tag === 'react/fragment' || tag === '') {
      return React.createElement(React.Fragment, reactProps.key ? { key: reactProps.key } : null, ...renderedChildren);
    }

    return React.createElement(tag, reactProps, ...renderedChildren);
  }

  return null;
}

export function render(hiccupComponent, container) {
  const root = ReactDOM.createRoot(container);

  const AppRunner = () => {
    const [_, setTick] = useState(0);

    useEffect(() => {
      const update = () => setTick(t => t + 1);
      updateListeners.add(update);
      return () => {
        updateListeners.delete(update);
      };
    }, []);

    return hiccupToReact(hiccupComponent);
  };

  root.render(React.createElement(AppRunner, null));
}
