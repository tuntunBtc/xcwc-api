const { chromium } = require('@playwright/test');
(async()=>{const b=await chromium.launch({channel:'chrome',headless:true});const page=await b.newPage();await page.goto('about:blank');console.log('Chrome browser ready');await b.close()})();
