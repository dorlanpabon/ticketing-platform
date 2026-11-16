"""Exercise the real local HTTP, DynamoDB and queue paths; never manufacture evidence."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
URLS={'catalog':'http://127.0.0.1:18080','orders':'http://127.0.0.1:18081','inventory':'http://127.0.0.1:18082'}
COMPOSE=['docker','compose','-f',str(ROOT/'docker-compose.yml')]
LOCAL_SQS_ENDPOINT='http://elasticmq:9324'
INVENTORY_COMMANDS_URL=LOCAL_SQS_ENDPOINT+'/000000000000/inventory-commands'
LOCAL_HOST_ENDPOINTS = {
    'http://dynamodb:8000': 'http://127.0.0.1:18000',
    LOCAL_SQS_ENDPOINT: 'http://127.0.0.1:19324',
}

def http(service,path,method='GET',body=None,identity='local-customer-1',key=None):
    headers={'Authorization':'Bearer '+identity,'Content-Type':'application/json'}
    if key: headers['Idempotency-Key']=key
    request=urllib.request.Request(URLS[service]+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
    try:
        with urllib.request.urlopen(request,timeout=15) as response:
            data=response.read(); return response.status,json.loads(data) if data else None
    except urllib.error.HTTPError as error:
        data=error.read(); return error.code,json.loads(data) if data else None

def require(condition,message):
    if not condition: raise AssertionError(message)

def eventually(callback,predicate,timeout=30):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        result=callback()
        if predicate(result): return result
        time.sleep(0.2)
    raise AssertionError('Timed out waiting for durable asynchronous state')

def event(capacity):
    status,body=http('catalog','/api/v1/events','POST',{'name':'Acceptance '+uuid.uuid4().hex,'venue':'Local verification','startsAt':(datetime.now(timezone.utc)+timedelta(days=30)).isoformat(),'capacity':capacity},'local-admin',uuid.uuid4().hex)
    require(status==202,f'Event creation returned {status}: {body}')
    event_id=body['eventId']
    eventually(lambda:http('catalog','/api/v1/events/'+event_id),lambda result:result[0]==200 and result[1]['status']=='ACTIVE')
    return event_id

def purchase(event_id,tickets,key=None,identity='local-customer-1'):
    result=http('orders','/api/v1/orders','POST',{'eventId':event_id,'ticketIds':tickets},identity,key or uuid.uuid4().hex)
    require(result[0]==202,f'Purchase was not durably accepted: {result}')
    return result[1]

def order(order_id,workflow=None,timeout=30):
    result=eventually(lambda:http('orders','/api/v1/orders/'+order_id),lambda result:result[0]==200 and (result[1]['workflowStatus']!='RESERVING' if workflow is None else result[1]['workflowStatus']==workflow),timeout)
    return result[1]

def integration():
    event_id=event(12); key=uuid.uuid4().hex
    first=purchase(event_id,['1','2'],key)
    duplicate=purchase(event_id,['2','1'],key)
    require(first['orderId']==duplicate['orderId'],'Idempotent retries produced different orders')
    require(first['expiresAt']==duplicate['expiresAt'],'Retry extended reservation deadline')
    require(http('orders','/api/v1/orders','POST',{'eventId':event_id,'ticketIds':['3']},key=key)[0]==409,'Idempotency conflict was not rejected')
    held=order(first['orderId'],'AWAITING_CONFIRMATION'); require(len(held['activeTicketIds'])==2,'Partial reservation')
    require(http('orders','/api/v1/orders/'+first['orderId'],identity='local-customer-2')[0]==404,'Owner isolation failed')
    require(http('orders','/api/v1/orders/'+first['orderId']+'/confirm','POST',key=uuid.uuid4().hex)[0]==202,'Confirmation was not accepted')
    sold=order(first['orderId'],'COMPLETED'); require(sold['status']=='SOLD','Confirmation did not sell tickets')
    require(http('orders','/api/v1/orders/'+first['orderId']+'/confirm','POST',key=uuid.uuid4().hex)[0]==202,'Repeated confirmation failed')
    complimentary={'eventId':event_id,'ticketIds':['3'],'beneficiaryId':'local-customer-1','reason':'Acceptance speaker'}
    require(http('orders','/api/v1/admin/complimentary-orders','POST',complimentary,key=uuid.uuid4().hex)[0]==403,'Customer gained admin allocation')
    code,courtesy=http('orders','/api/v1/admin/complimentary-orders','POST',complimentary,'local-admin',uuid.uuid4().hex)
    require(code==202,'Admin allocation not accepted'); require(order(courtesy['orderId'],'COMPLETED')['status']=='COMPLIMENTARY','Courtesy was not final')
    rejected=purchase(event_id,['2','4']); require(order(rejected['orderId'])['workflowStatus']=='REJECTED','Sold seat was reassigned')
    code,available=http('inventory','/api/v1/events/'+event_id+'/availability'); require(code==200,'Availability missing')
    require(available['sold']==2 and available['complimentary']==1 and available['available']==9,'Counter invariant or courtesy/sales separation failed')
    tickets=http('inventory','/api/v1/events/'+event_id+'/tickets?limit=100')[1]['items']
    require(next(item for item in tickets if item['ticketId']=='4')['status']=='AVAILABLE','Rejected multi-seat request partially reserved a seat')
    return {'eventId':event_id,'orderId':first['orderId'],'assertions':['durable202','idempotency','deadline','owner','confirmation','courtesy','all-or-none','counters']}

def concurrency():
    event_id = event(1)

    def buy(ticket):
        key = uuid.uuid4().hex
        body = {'eventId': event_id, 'ticketIds': [ticket]}
        for retries in range(4):
            status, result = http('orders', '/api/v1/orders', 'POST', body, key=key)
            if status == 202:
                return result, retries
            require(
                status == 503 and (result or {}).get('code') == 'ORDER_ENQUEUE_UNAVAILABLE',
                f'Unexpected concurrent purchase response: {status} {result}',
            )
            if retries < 3:
                time.sleep(0.5 * (retries + 1))
        raise AssertionError('SQS acknowledgement did not arrive after idempotent retries')

    with ThreadPoolExecutor(max_workers=40) as pool:
        attempts = list(pool.map(buy, ['1'] * 100))
    results = [result for result, _ in attempts]
    require(len({result['orderId'] for result in results}) == 100, 'Expected 100 distinct orders')
    outcomes=[order(result['orderId']) for result in results]
    winners=[result for result in outcomes if result['status']=='RESERVED']
    require(len(winners)==1,'Concurrent requests did not produce exactly one reservation')
    require(all(result['workflowStatus'] in ('AWAITING_CONFIRMATION','REJECTED') for result in outcomes),'Unexpected terminal outcome')
    snapshot=http('inventory','/api/v1/events/'+event_id+'/availability')[1]
    require(snapshot['reserved']==1 and snapshot['available']==0,'Counter drift under concurrency')
    return {'eventId': event_id, 'buyers': 100, 'winners': len(winners), 'rejected': 99,
            'enqueueRetries': sum(retries for _, retries in attempts),
            'buyersWithRetry': sum(retries > 0 for _, retries in attempts)}

def recovery():
    event_id=event(1)
    command=COMPOSE
    workers=subprocess.check_output([*command,'ps','--status','running','-q','inventory-worker'],text=True).splitlines()
    require(len(workers)>=2,'Recovery requires at least two running Inventory consumers')
    subprocess.run([*command,'stop','inventory','inventory-worker'],check=True,capture_output=True)
    try:
        accepted=purchase(event_id,['1']); require(accepted['workflowStatus']=='RESERVING','Offline authority was presented as a confirmed hold')
    finally:
        subprocess.run([*command,'up','-d','--no-deps','--wait','--wait-timeout','180',
                        '--scale',f'inventory-worker={len(workers)}','inventory','inventory-worker'],check=True,capture_output=True)
    result=order(accepted['orderId'],'AWAITING_CONFIRMATION',timeout=120)
    require(result['expiresAt']==accepted['expiresAt'],'Recovery extended the deadline')
    return {'eventId':event_id,'orderId':accepted['orderId'],'scenario':'Inventory API and all consumers unavailable then restored',
            'consumerReplicas':len(workers),'deadlinePreserved':True}

def container_smoke():
    for service in URLS:
        code,body=http(service,'/actuator/health/readiness'); require(code==200 and body['status']=='UP',service+' not ready')
    return {'services':list(URLS),'readiness':'UP'}

def architecture():
    reports={}
    for service in URLS:
        folder=ROOT/'services'/service
        wrapper=folder/('gradlew.bat' if os.name=='nt' else 'gradlew')
        subprocess.run([str(wrapper),'--no-daemon','--no-configuration-cache',':app-service:test',
                        '--tests',f'com.ticketing.{service}.StrictArchitectureTest','--rerun-tasks'],
                       cwd=folder,check=True,capture_output=True,timeout=600)
        report=folder/'applications/app-service/build/test-results/test'/f'TEST-com.ticketing.{service}.StrictArchitectureTest.xml'
        suite=ET.parse(report).getroot()
        require(int(suite.get('tests','0'))==3 and int(suite.get('failures','0'))==0
                and int(suite.get('errors','0'))==0 and int(suite.get('skipped','0'))==0,
                service+' architecture boundaries failed')
        reports[service]={'tests':3,'report':str(report.relative_to(ROOT)),
                          'sha256':hashlib.sha256(report.read_bytes()).hexdigest()}
    return {'services':reports,'rules':['domain-dependencies','bounded-context-isolation','nonblocking-reactive-code']}

def local_aws(endpoint, *arguments):
    aws = shutil.which('aws')
    host_endpoint = LOCAL_HOST_ENDPOINTS.get(endpoint)
    if aws and host_endpoint:
        env = dict(
            os.environ, AWS_REGION='us-east-1', AWS_ACCESS_KEY_ID='localemulator',
            AWS_SECRET_ACCESS_KEY='localemulator', AWS_EC2_METADATA_DISABLED='true',
            AWS_MAX_ATTEMPTS='1', AWS_CONFIG_FILE=os.devnull, AWS_SHARED_CREDENTIALS_FILE=os.devnull,
        )
        for key in ('AWS_SESSION_TOKEN', 'AWS_SECURITY_TOKEN', 'AWS_PROFILE', 'AWS_DEFAULT_PROFILE'):
            env.pop(key, None)
        local_arguments = [arg.replace(endpoint, host_endpoint, 1) if arg.startswith(endpoint + '/')
                           else arg for arg in arguments]
        command = [aws, '--endpoint-url', host_endpoint, '--no-cli-pager', '--output', 'json',
                   '--cli-connect-timeout', '3', '--cli-read-timeout', '8', *local_arguments]
    else:
        env = None
        command = [*COMPOSE, 'run', '--rm', '--no-deps', '--entrypoint', 'aws', 'bootstrap',
                   '--endpoint-url', endpoint, '--no-cli-pager', '--output', 'json', *arguments]
    result = subprocess.run(command, env=env, check=True, capture_output=True, text=True, timeout=30)
    return json.loads(result.stdout.strip() or '{}')

def local_row(table, partition, sort='META'):
    result=local_aws('http://dynamodb:8000','dynamodb','get-item','--table-name',table,
                     '--key',json.dumps({'pk':{'S':partition},'sk':{'S':sort}}),'--consistent-read')
    return result.get('Item',{})

def local_inventory_row(partition,sort='META'):
    return local_row('ticketing-local-inventory',partition,sort)

def queue_outage():
    """Stop the local broker and isolate the API's publication from the relay and consumer."""
    event_id=event(2)
    key=uuid.uuid4().hex
    table='ticketing-local-orders'
    actor='local-customer-1'
    idempotency='IDEMP#'+hashlib.sha256(f'{actor}\nPURCHASE\n{key}'.encode('utf-8')).hexdigest()
    workers=['orders-worker','inventory-worker']
    replicas={service:len(subprocess.check_output([*COMPOSE,'ps','--status','running','-q',service],
                                                   text=True).splitlines()) for service in workers}
    require(all(replicas.values()),'Queue outage test requires running Orders and Inventory workers')
    scale=[argument for service,count in replicas.items() for argument in ('--scale',f'{service}={count}')]

    def queue_ready():
        try:
            return local_aws(LOCAL_SQS_ENDPOINT,'sqs','get-queue-attributes',
                             '--queue-url',INVENTORY_COMMANDS_URL,'--attribute-names','QueueArn')
        except (subprocess.CalledProcessError,subprocess.TimeoutExpired):
            return {}

    try:
        subprocess.run([*COMPOSE,'stop',*workers,'elasticmq'],check=True,capture_output=True)
        status,error=http('orders','/api/v1/orders','POST',{'eventId':event_id,'ticketIds':['1']},actor,key)
        require(status==503 and (error or {}).get('code')=='ORDER_ENQUEUE_UNAVAILABLE',
                f'Unconfirmed enqueue returned {status}: {error}')

        intent=local_row(table,idempotency)
        require('entityId' in intent and 'purchaseMessageId' in intent,'Missing durable idempotency link')
        order_id=intent['entityId']['S']
        message_id=intent['purchaseMessageId']['S']
        stored=json.loads(local_row(table,'ORDER#'+order_id)['data']['S'])
        envelope=json.loads(local_row(table,'OUTBOX#'+message_id)['data']['S'])
        require(stored['orderId']==order_id and stored['workflowStatus']=='RESERVING','Order was not persisted')
        require(envelope['messageId']==message_id and envelope['aggregateId']==order_id
                and envelope['type']=='PurchaseRequested' and envelope['payload']['expiresAt']==stored['expiresAt'],
                'Pending outbox did not preserve the initial business intent')
        require(http('orders','/api/v1/orders','POST',{'eventId':event_id,'ticketIds':['2']},actor,key)[0]==409,
                'Conflicting idempotency payload was accepted during the outage')

        subprocess.run([*COMPOSE,'up','-d','--no-deps','elasticmq'],check=True,capture_output=True)
        eventually(queue_ready,lambda result:bool(result.get('Attributes',{}).get('QueueArn')),timeout=60)
        retry_attempts = 0

        def retry_purchase():
            nonlocal retry_attempts
            retry_attempts += 1
            code, body = http('orders', '/api/v1/orders', 'POST',
                              {'eventId': event_id, 'ticketIds': ['1']}, actor, key)
            require(code == 202 or (code == 503 and (body or {}).get('code') == 'ORDER_ENQUEUE_UNAVAILABLE'),
                    f'Unexpected retry response after restoring the broker: {code} {body}')
            if code == 503:
                time.sleep(1)
            return code, body

        _, accepted = eventually(retry_purchase, lambda result: result[0] == 202, timeout=90)
        require(accepted['orderId']==order_id and accepted['expiresAt']==stored['expiresAt'],
                'Retry changed the durable order ID or deadline')
        messages=local_aws(LOCAL_SQS_ENDPOINT,'sqs','receive-message','--queue-url',INVENTORY_COMMANDS_URL,
                           '--max-number-of-messages','10','--visibility-timeout','0','--wait-time-seconds','2')
        require(any(json.loads(message['Body'])==envelope for message in messages.get('Messages',[])),
                'API returned 202 with the workers stopped, but the original command was missing from SQS')
    finally:
        subprocess.run([*COMPOSE,'up','-d','--no-deps','--wait','--wait-timeout','180',
                        *scale,'elasticmq',*workers],check=True,capture_output=True)
    held=order(order_id,'AWAITING_CONFIRMATION',timeout=120)
    require(held['expiresAt']==stored['expiresAt'],'Consumer recovery changed the initial deadline')
    return {'eventId':event_id,'orderId':order_id,'messageId':message_id,'outageStatus':503,'retryStatus':202,
            'conflictingPayloadStatus':409,'deadlinePreserved':True,'sameEnvelopeObservedInSqs':True,
            'retryAttemptsAfterRestore': retry_attempts}

def expiry():
    """Observe a real delayed SQS timer without HTTP lazy-expiry or periodic recovery."""
    event_id=event(2)
    pending=purchase(event_id,['1']); order(pending['orderId'],'AWAITING_CONFIRMATION')
    bought=purchase(event_id,['2']); order(bought['orderId'],'AWAITING_CONFIRMATION')
    require(http('orders','/api/v1/orders/'+bought['orderId']+'/confirm','POST',key=uuid.uuid4().hex)[0]==202,'Confirmation rejected')
    order(bought['orderId'],'COMPLETED')
    deadline=datetime.fromisoformat(pending['expiresAt'].replace('Z','+00:00'))
    command=COMPOSE
    since=datetime.now(timezone.utc).isoformat()
    subprocess.run([*command,'stop','inventory','expiry-recovery'],check=True,capture_output=True)
    try:
        while datetime.now(timezone.utc)<deadline:
            remaining=(deadline-datetime.now(timezone.utc)).total_seconds()
            time.sleep(min(5,max(0,remaining)))
        row=eventually(lambda:local_inventory_row('RESERVATION#'+pending['orderId']),
                       lambda item:item.get('state',{}).get('S')=='AVAILABLE',timeout=30)
        expired=json.loads(row['data']['S'])
        require(expired['resolution']=='RESERVATION_EXPIRED' and not expired['activeTicketIds'],'Timer did not release the whole hold')
        require(expired['expiresAt']==pending['expiresAt'],'Timer changed original deadline')
        sold=json.loads(local_inventory_row('RESERVATION#'+bought['orderId'])['data']['S'])
        require(sold['status']=='SOLD','Expiration released a sold ticket')
        logs=subprocess.check_output([*command,'logs','--no-color','--no-log-prefix','--since',since,'inventory-worker'],text=True)
        observed=[]
        for line in logs.splitlines():
            try: entry=json.loads(line)
            except json.JSONDecodeError: continue
            if entry.get('operation')=='ExpireReservation' and entry.get('reservationId')==pending['orderId']:
                observed.append(entry)
        require(observed,'No actual SQS expiration processing evidence was emitted')
        processed=datetime.fromisoformat(observed[0]['@timestamp'].replace('Z','+00:00'))
        lag=(processed-deadline).total_seconds()
        require(lag>=0,'Reservation was released before its deadline')
    finally:
        subprocess.run([*command,'up','-d','--no-deps','--wait','--wait-timeout','180','inventory','expiry-recovery'],
                       check=True,capture_output=True)
    return {'eventId':event_id,'orderId':pending['orderId'],'expiresAt':pending['expiresAt'],
            'timerProcessingLagSeconds':lag,'periodicRecoveryStopped':True,'httpLazyExpiryDisabled':True,
            'durableExpiredState':expired['resolution'],'soldTicketPreserved':True}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=ROOT/'build/quality/acceptance.json')
    parser.add_argument('--allow-dirty',action='store_true')
    parser.add_argument('--checks',nargs='+',choices=['containerSmoke','architecture','integration','concurrency','recovery','expiry','queueOutage'],default=['containerSmoke','integration','concurrency','recovery','architecture','expiry'])
    args=parser.parse_args()
    args.output=args.output.resolve()
    def git(*values):return subprocess.check_output(['git','-C',str(ROOT),*values],text=True).strip()
    dirty=bool(git('status','--porcelain'))
    if dirty and not args.allow_dirty:raise SystemExit('Commit the verified candidate first, or use --allow-dirty for non-promotable development evidence.')
    document={'commit':git('rev-parse','HEAD'),'tree':git('rev-parse','HEAD^{tree}'),'dirty':dirty,'startedAt':datetime.now(timezone.utc).isoformat(),'environment':'local-emulators','checks':{}}
    evidence=args.output.parent/(args.output.stem+'-evidence'); evidence.mkdir(parents=True,exist_ok=True)
    def unchanged():
        if not args.allow_dirty:
            require(git('rev-parse','HEAD')==document['commit'] and not git('status','--porcelain'),
                    'Candidate changed while acceptance was running')
    for name in args.checks:
        callback={'containerSmoke':container_smoke,'architecture':architecture,'integration':integration,'concurrency':concurrency,'recovery':recovery,'expiry':expiry,'queueOutage':queue_outage}[name]
        try:
            unchanged()
            data=callback()
            unchanged()
            data['status']='passed'
        except Exception as error:
            data={'status':'failed','error':str(error)}
        file=evidence/(name+'.json'); file.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')
        document['checks'][name]={'status':data['status'],'evidence':str(file.relative_to(ROOT)) if file.is_relative_to(ROOT) else str(file)}
        args.output.parent.mkdir(parents=True,exist_ok=True); args.output.write_text(json.dumps(document,indent=2)+'\n',encoding='utf-8')
        print(name+': '+data['status'])
        if data['status']!='passed':raise SystemExit(data['error'])

if __name__=='__main__':main()
