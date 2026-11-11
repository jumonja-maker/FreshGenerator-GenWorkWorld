# Only base R. Raw third-party point records stay under build/, outside distributable/source archives.
args <- commandArgs(trailingOnly=TRUE); folder <- args[1]
set.seed(20261111)
for (name in c('lansing','hyytiala','bei')) {
 env <- new.env(); load(file.path(folder,paste0(name,'.rda')),envir=env)
 obj <- get(name,envir=env); x <- obj$x; y <- obj$y
 scale <- if(name=='lansing') 924*0.3048 else 1
 x <- x*scale; y <- y*scale
 xr <- obj$window$xrange*scale; yr <- obj$window$yrange*scale
 stopifnot(length(x)==length(y),length(x)>0,all(is.finite(x)),all(is.finite(y)))
 stopifnot(all(x>=xr[1]&x<=xr[2]&y>=yr[1]&y<=yr[2]))
 n <- length(x); w <- diff(xr); h <- diff(yr)
 d <- as.matrix(dist(cbind(x,y))); diag(d) <- Inf; nn <- apply(d,1,min); rm(d)
 border <- pmin(x-xr[1],xr[2]-x,y-yr[1],yr[2]-y)
 complete <- nn<border
 stats <- c(records=n,window_width_m=w,window_height_m=h,area_m2=w*h,
  stems_per_ha_observed=n/(w*h)*10000,duplicate_records=sum(duplicated(cbind(x,y))),
  nearest_median_m=median(nn),nearest_q10_m=unname(quantile(nn,.1)),nearest_q90_m=unname(quantile(nn,.9)),
  nearest_boundary_complete_count=sum(complete),nearest_boundary_complete_median_m=median(nn[complete]))
 for (k in c(4,8,16)) {
   counts <- function(xx,yy) tabulate(pmin(k-1,floor((xx-xr[1])/w*k))+k*pmin(k-1,floor((yy-yr[1])/h*k))+1,nbins=k*k)
   q <- counts(x,y)
   controls <- replicate(64,{qc<-counts(runif(n,xr[1],xr[2]),runif(n,yr[1],yr[2])); c(var(qc)/mean(qc),mean(qc==0))})
   vals <- c(var(q)/mean(q),mean(q==0),median(controls[1,]),unname(quantile(controls[1,],.025)),unname(quantile(controls[1,],.975)),median(controls[2,]))
   names(vals) <- paste0('grid',k,'_',c('variance_mean','empty_fraction','uniform_control_median_vm','uniform_control_q025_vm','uniform_control_q975_vm','uniform_control_empty_fraction'))
   stats <- c(stats,vals)
 }
 marks <- obj$marks
 if(is.null(marks)) marks <- rep('Beilschmiedia_pendula',n)
 if(is.data.frame(marks)) marks <- marks[[1]]
 species <- as.data.frame(table(as.character(marks)),stringsAsFactors=FALSE);names(species)<-c('species','count')
 write.csv(data.frame(metric=names(stats),value=unname(stats)),file.path(folder,paste0(name,'-summary.csv')),row.names=FALSE)
 write.csv(species,file.path(folder,paste0(name,'-species.csv')),row.names=FALSE)
 cat('RESEARCH_DATASET',name,'n=',n,'duplicate=',sum(duplicated(cbind(x,y))),'\n')
}
