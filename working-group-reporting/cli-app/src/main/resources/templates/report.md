# Working Group activities from {from} to {to}


{#if !groups.getWorkingGroupsCreatedInPeriod().isEmpty()}
## New working groups

These working groups were recently created. 
Check them out!

{#for wg in groups.getWorkingGroupsCreatedInPeriod()}
* [{wg.name}]({wg.url})
{/for}

{/if}

{#if !groups.getWorkingGroupsCompletedInPeriod().isEmpty()}

## Completed working groups

These working groups are now completed.

{#for wg in groups.getWorkingGroupsCompletedInPeriod()}
* [{wg.name}]({wg.url})
  {/for}

{/if}

## Latest updates

{#for wg in groups}
{#if !wg.isCompleted() && !wg.isPaused()  && !wg.isLTS()}
### {wg.getStatus().icon()}  [{wg.name}]({wg.url})

{#let last=groups.getLastUpdate(wg, true)}
{#if last && last.body.trim()}
{last.body.trim()}

{/if}
{/let}
{/if}
{/for}

## Detailed activity

{#for wg in groups}
    {#let newItems=groups.getNewItems(wg) completedItems=groups.getCompletedItems(wg)}

    {#if !newItems.isEmpty() || !completedItems.isEmpty()}
### [{wg.name}]({wg.url})

        {#if !newItems.isEmpty()}
### New items

            {#for item in newItems}
- [{item.title}]({item.url})
            {/for}
        {/if}

        {#if !completedItems.isEmpty()}
### Completed items

            {#for item in completedItems}
- [{item.title}]({item.url})
            {/for}
        {/if}
    {/if} {/let}
{/for}
## Paused working groups

{#for wg in groups}
    {#if wg.isPaused()}
* [{wg.name}]({wg.url})
    {/if}
{/for}

## Completed working groups

{#for wg in groups}
    {#if wg.isCompleted()}
* [{wg.name}]({wg.url}) ({groups.getCompletionDate(wg)})
    {/if}
{/for}
