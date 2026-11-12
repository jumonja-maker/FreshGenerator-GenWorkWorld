"""Read-only difference details: never used to exempt blocks from equality checks."""
from collections import Counter

def full_differences(before,after):
 rows=[]
 for old,new in zip(before['full_windows'],after['full_windows']):
  row={'label':old['label'],'x':old['x'],'z':old['z'],
       'changed_fields':[k for k in old.keys()|new.keys() if old.get(k)!=new.get(k)]}
  if 'sampled_states' in old and 'sampled_states' in new:
   pairs=Counter();examples=[];count=0
   if len(old['sampled_states'])!=len(new['sampled_states']):row['different_sample_count']=True
   else:
    for i,(a,b) in enumerate(zip(old['sampled_states'],new['sampled_states'])):
     a=old['block_palette'][a];b=new['block_palette'][b]
     if a==b:continue
     count+=1;pairs[(a,b)]+=1
     if len(examples)<16:
      column,y=divmod(i,384);dx,dz=divmod(column,16)
      examples.append({'x':old['x']+dx*4,'y':y-64,'z':old['z']+dz*4,'before':a,'after':b})
    row.update(changed_blocks=count,common_changes=[{'before':a,'after':b,'count':n} for (a,b),n in pairs.most_common(16)],examples=examples)
  if row['changed_fields']:rows.append(row)
 return rows
