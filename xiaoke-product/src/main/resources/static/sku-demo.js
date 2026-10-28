"use strict";
const $=id=>document.getElementById(id);
let selected=null,pending=null,busy=false;
function message(text){$("message").textContent=text;}
function freeze(value){$("fields").disabled=value;$("load").disabled=value;$("options").querySelectorAll("button").forEach(b=>b.disabled=value||b.dataset.sold==="yes");}
async function request(url,options={}){
  const response=await fetch(url,{...options,signal:AbortSignal.timeout(10000)});
  const body=await response.json();
  if(!response.ok||body.code!==200){const error=new Error(body.message||"请求未完成");error.status=response.status;throw error;}
  return body.data;
}
$("load").onclick=async()=>{
  if(pending||busy)return;
  const id=$("productId").value;if(!/^[1-9][0-9]*$/.test(id)){message("请输入有效的商品编号");return;}
  selected=null;$("checkout").hidden=true;$("product").hidden=true;
  try{
    const product=await request(`product/${encodeURIComponent(id)}`);
    $("name").textContent=product.name;$("description").textContent=product.description||"";$("options").replaceChildren();
    product.skus.forEach(sku=>{
      const button=document.createElement("button");button.type="button";
      button.textContent=Object.entries(sku.specs).map(([k,v])=>`${k}：${v}`).join(" / ")||sku.name;
      button.dataset.sold=sku.stock<1?"yes":"no";button.disabled=sku.stock<1;button.setAttribute("aria-pressed","false");
      button.onclick=()=>{selected=sku;$("options").querySelectorAll("button").forEach(b=>b.setAttribute("aria-pressed",String(b===button)));
        $("price").textContent=`¥ ${Number(sku.price).toFixed(2)}`;$("stock").textContent=`当前规格库存 ${sku.stock} 件`;$("checkout").hidden=false;};
      $("options").append(button);
    });
    $("product").hidden=false;$("price").textContent="请选择规格";$("stock").textContent="";message(product.skus.some(s=>s.stock>0)?"":"该商品暂时无可购买规格");
  }catch(error){message(error.message||"商品暂时无法加载");}
};
function success(value){message("订单已创建。重复提交将返回同一笔订单。");$("result").hidden=false;$("result").textContent=`订单号：${value.orderNo}\n创建金额：¥ ${Number(value.totalAmount).toFixed(2)}`;$("submit").disabled=true;$("lookup").hidden=false;$("new").hidden=false;}
$("checkout").onsubmit=async event=>{
  event.preventDefault();if(busy||!selected)return;
  if(!pending){
    const quantity=Number($("quantity").value);if(!Number.isInteger(quantity)||quantity<1||quantity>999){message("购买数量应为1至999的整数");return;}
    const base=new URL($("orderBase").value);if(!["http:","https:"].includes(base.protocol)||base.username||base.password){message("订单服务地址不合法");return;}
    pending={key:crypto.randomUUID(),base:base.href.replace(/\/$/,""),token:$("token").value,
      body:{items:[{skuId:selected.id,quantity}],receiverName:$("receiverName").value,receiverPhone:$("receiverPhone").value,receiverAddress:$("receiverAddress").value,remark:$("remark").value}};
    freeze(true);
  }
  busy=true;$("submit").disabled=true;message("正在提交订单…");
  try{success(await request(`${pending.base}/order`,{method:"POST",headers:{"Content-Type":"application/json","Authorization":pending.token,"Idempotency-Key":pending.key},body:JSON.stringify(pending.body)}));}
  catch(error){message(`${error.message||"暂未收到提交结果"}。请先查询提交结果，重试会保留本次信息。`);$("lookup").hidden=false;$("submit").disabled=false;$("submit").textContent="重试本次提交";
    if(!pending.uncertain&&[400,401,403,404,409].includes(error.status)){$("new").hidden=false;$("new").textContent="重新填写";}
    else{pending.uncertain=true;$("new").hidden=true;}}
  finally{busy=false;}
};
$("lookup").onclick=async()=>{if(!pending||busy)return;busy=true;try{success(await request(`${pending.base}/order/idempotency/${pending.key}`,{headers:{Authorization:pending.token}}));}catch(error){message(error.status===404?"暂未查到已提交结果，请稍后再查或重试本次提交。":error.message);}finally{busy=false;}};
$("new").onclick=()=>{if(busy)return;pending=null;freeze(false);$("result").hidden=true;$("lookup").hidden=true;$("new").hidden=true;$("submit").disabled=false;$("submit").textContent="确认下单";message("可以开始另一笔订单，请重新确认规格和库存。");};
