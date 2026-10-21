import { injectGlobalWebcomponentCss } from 'Frontend/generated/jar-resources/theme-util.js';

import '@vaadin/login/src/vaadin-login-form.js';
import '@vaadin/side-nav/src/vaadin-side-nav.js';
import '@vaadin/side-nav/src/vaadin-side-nav-item.js';
import '@vaadin/tooltip/src/vaadin-tooltip.js';
import '@vaadin/icons/vaadin-iconset.js';
import '@vaadin/icon/src/vaadin-icon.js';
import '@vaadin/button/src/vaadin-button.js';
import 'Frontend/generated/jar-resources/disableOnClickFunctions.js';
import '@vaadin/common-frontend/ConnectionIndicator.js';
import 'Frontend/generated/jar-resources/ReactRouterOutletElement.tsx';

const loadOnDemand = (key) => {
  const pending = [];
  if (key === '723f2b44a416eaeb36f91b1bb4774fdafe291197166c116d3bcb4482f96bdaae') {
    pending.push(import('./chunks/chunk-e7870069923a329c393e7b41832dea9828c07b28b3c46a539a2a4ca63a4140e7.js'));
  }
  if (key === '1532adaed471701c77a1cfea8f7004cbbec62670d0da4deb0ee0db9df69a1d9c') {
    pending.push(import('./chunks/chunk-e7870069923a329c393e7b41832dea9828c07b28b3c46a539a2a4ca63a4140e7.js'));
  }
  if (key === '594b1bc20b897494f5f3b490cb0ec99da128a6eba299ae43071f79ed31d221ff') {
    pending.push(import('./chunks/chunk-e7870069923a329c393e7b41832dea9828c07b28b3c46a539a2a4ca63a4140e7.js'));
  }
  if (key === '8de6ebd940448a2ce7b22b1cc8fd734077f3ab48d175ea38b603dbc8af2d0893') {
    pending.push(import('./chunks/chunk-e7870069923a329c393e7b41832dea9828c07b28b3c46a539a2a4ca63a4140e7.js'));
  }
  return Promise.all(pending);
}
window.Vaadin = window.Vaadin || {};
window.Vaadin.Flow = window.Vaadin.Flow || {};
window.Vaadin.Flow.loadOnDemand = loadOnDemand;
window.Vaadin.Flow.resetFocus = () => {
 let ae=document.activeElement;
 while(ae&&ae.shadowRoot) ae = ae.shadowRoot.activeElement;
 return !ae || ae.blur() || ae.focus() || true;
}