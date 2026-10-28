// Exercise the actual page handlers with a tiny DOM and controlled network outcomes.
// This validates key retention and selection behavior, not browser layout or live CORS.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {webcrypto} = require('node:crypto');
const root = 'xiaoke-product/src/main/resources/static/';
class Element {
  constructor(){this.value='';this.hidden=false;this.disabled=false;this.children=[];this.dataset={};this.attrs={};}
  setAttribute(k,v){this.attrs[k]=v;}
  replaceChildren(){this.children=[];}
  append(child){this.children.push(child);}
  querySelectorAll(){return this.children;}
}
const ids=[...fs.readFileSync(root+'sku-demo.html','utf8').matchAll(/id="([^"]+)"/g)].map(m=>m[1]);
const elements=Object.fromEntries(ids.map(id=>[id,new Element()]));
for(const id of ['product','checkout','lookup','new','result'])elements[id].hidden=true;
const outcomes=[],calls=[];
const context={document:{getElementById:id=>elements[id],createElement:()=>new Element()},
  crypto:webcrypto,URL,AbortSignal,fetch:async(url,options={})=>{
    calls.push({url,headers:options.headers,body:options.body});
    const next=outcomes.shift();assert.ok(next,'Unexpected request');if(next instanceof Error)throw next;
    return {ok:next.code===200,status:next.code,json:async()=>next};
  }};
vm.createContext(context);vm.runInContext(fs.readFileSync(root+'sku-demo.js','utf8'),context);
async function run(){
  elements.productId.value='7';
  outcomes.push({code:200,data:{name:'Test phone',skus:[{id:11,name:'black',specs:{color:'black'},price:2.50,stock:3},{id:12,name:'white',specs:{color:'white'},price:4,stock:0}]}});
  await elements.load.onclick();assert.equal(elements.options.children[1].disabled,true);
  elements.options.children[0].onclick();assert.equal(elements.price.textContent,'¥ 2.50');assert.equal(elements.checkout.hidden,false);
  for(const [id,value] of Object.entries({quantity:'2',receiverName:'A',receiverPhone:'000',receiverAddress:'Road',remark:'',orderBase:'http://127.0.0.1:8082/api',token:'temporary-test-token'}))elements[id].value=value;
  outcomes.push(new TypeError('Network disconnected'));
  await elements.checkout.onsubmit({preventDefault(){}});
  const first=calls.at(-1);assert.equal(JSON.parse(first.body).items[0].skuId,11);
  assert.equal(elements.fields.disabled,true);assert.equal(elements.new.hidden,true);
  outcomes.push({code:404,message:'not visible yet'});await elements.lookup.onclick();
  assert.equal(elements.new.hidden,true,'404 must not silently abandon a possibly committed key');
  outcomes.push({code:200,data:{orderNo:'20261028000001',totalAmount:5,status:0}});
  await elements.checkout.onsubmit({preventDefault(){}});
  const retry=calls.at(-1);assert.equal(retry.headers['Idempotency-Key'],first.headers['Idempotency-Key']);assert.equal(retry.body,first.body);
  assert.equal(elements.new.hidden,false);assert.equal(elements.submit.disabled,true);
  elements.new.onclick();assert.equal(elements.fields.disabled,false);
  outcomes.push({code:200,data:{orderNo:'20261028000002',totalAmount:5,status:0}});
  await elements.checkout.onsubmit({preventDefault(){}});
  assert.notEqual(calls.at(-1).headers['Idempotency-Key'],first.headers['Idempotency-Key']);
  assert.equal(outcomes.length,0);
  console.log('PASS SKU page: selection, sold-out option, frozen intent, unknown/404 key retention, replay, explicit new order');
}
run().catch(error=>{console.error(error);process.exitCode=1;});
